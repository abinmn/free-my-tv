#!/usr/bin/env python3
"""Pick a Jellyfin user and TV, then send their connection through ADB without embedding a key in the APK."""
import argparse
import getpass
import ipaddress
import json
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

import send_key


def validate_server(server):
    address = urllib.parse.urlsplit(server)
    if address.scheme not in ('http', 'https') or not address.hostname or address.username is not None or address.password is not None:
        raise ValueError('Set jellyfin_server in config.properties, then build and install that APK first.')
    if address.query or address.fragment:
        raise ValueError('jellyfin_server must not contain a query or fragment.')
    if address.scheme == 'http':
        host = address.hostname.lower()
        try:
            ip = ipaddress.ip_address(host)
            local = any(ip in ipaddress.ip_network(net) for net in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
        except ValueError:
            local = bool(re.fullmatch(r'[a-z0-9-]+|[a-z0-9.-]+\.(local|lan|home\.arpa)', host))
        if not local:
            raise ValueError('Plain HTTP is only allowed on your own network; use HTTPS elsewhere.')
    return server.rstrip('/')


class Jellyfin:
    def __init__(self, server, token):
        if not re.fullmatch(r'[A-Za-z0-9._-]{8,1024}', token):
            raise ValueError('The API key must be one printable token.')
        self.server = server.rstrip('/')
        self.token = token
        self.opener = urllib.request.build_opener(send_key.NoRedirect)

    def get(self, path):
        request = urllib.request.Request(self.server + path, headers={
            'Authorization': f'MediaBrowser Token="{self.token}"',
        })
        with self.opener.open(request, timeout=10) as response:
            data = response.read(4_000_001)
            if len(data) > 4_000_000:
                raise ValueError('Jellyfin response was too large.')
            return json.loads(data)


def choose(items, label, describe, match=None):
    if match is not None:
        matches = [item for item in items if match(item)]
        if len(matches) != 1:
            raise ValueError(f'Expected exactly one matching {label}.')
        return matches[0]
    if not sys.stdin.isatty():
        raise ValueError('Use --user and --device-id when piping the key.')
    if not items:
        raise ValueError(f'No {label} available. Open Jellyfin on the TV and sign in first.')
    for index, item in enumerate(items, 1):
        print(f'  {index}. {describe(item)}')
    try:
        index = int(input(f'Choose the {label}: ')) - 1
    except ValueError:
        raise ValueError('Enter the number beside the desired entry.') from None
    if not 0 <= index < len(items):
        raise ValueError('That number is not in the list.')
    return items[index]


def connection(token, user, session):
    user_id = user['Id']
    if not re.fullmatch(r'[a-fA-F0-9-]{32,36}', user_id):
        raise ValueError('Jellyfin returned an invalid user ID.')
    if session.get('UserId', '').replace('-', '') != user_id.replace('-', ''):
        raise ValueError('The selected TV must be signed in as the selected user.')
    device_id = session.get('DeviceId', '')
    if not device_id or len(device_id) > 256:
        raise ValueError('Jellyfin returned an invalid device ID.')
    payload = json.dumps({'token': token, 'userId': user_id, 'deviceId': device_id},
                         ensure_ascii=True, separators=(',', ':')).replace(' ', '\\u0020')
    # Secrets accepts one compact printable line, at most 4 KB.
    if not re.fullmatch(r'[\x21-\x7e]{8,4000}', payload):
        raise ValueError('The connection cannot be sent as one compact printable line.')
    return payload.encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tv', help='TV IP address, optionally with its ADB port')
    parser.add_argument('--user', help='Exact Jellyfin account name')
    parser.add_argument('--device-id', help='Exact device ID of the TV running Jellyfin')
    args = parser.parse_args()
    if not shutil.which('adb'):
        raise ValueError('adb was not found; add Android platform-tools to PATH.')
    server = validate_server(send_key.prop('jellyfin_server'))
    token = getpass.getpass('Jellyfin API key (not shown): ') if sys.stdin.isatty() else sys.stdin.read().strip()
    api = Jellyfin(server, token)
    user = choose(api.get('/Users'), 'account', lambda u: u['Name'],
                  None if args.user is None else lambda u: u.get('Name') == args.user)
    sessions = [s for s in api.get('/Sessions')
                if s.get('Client') == 'Jellyfin Android TV' and s.get('SupportsRemoteControl')
                and s.get('UserId', '').replace('-', '') == user['Id'].replace('-', '')]
    session = choose(sessions, 'TV',
                     lambda s: f"{s.get('DeviceName', 'TV')} — {s.get('UserName', '')} — "
                               f"{s.get('LastActivityDate', '')} — device {s['DeviceId']}",
                     None if args.device_id is None else lambda s: s.get('DeviceId') == args.device_id)
    count = api.get('/LiveTv/Channels?' + urllib.parse.urlencode({'UserId': user['Id'], 'Limit': 1})).get('TotalRecordCount', 0)
    payload = connection(token, user, session)
    serial = args.tv if ':' in args.tv else args.tv + ':5555'
    result = subprocess.run(['adb', 'connect', serial], capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise ValueError('Could not connect to the TV through ADB.')
    send_key.SERIAL = ['-s', serial]
    version = send_key.adb('shell', 'getprop', 'ro.build.version.sdk', text=True)
    if not version.stdout.strip().isdigit() or int(version.stdout.strip()) < 28:
        raise ValueError('Sending a connection through ADB requires Android 9 or newer.')
    name = 'jellyfin_connection'
    before = send_key.received(name)
    result = send_key.adb('exec-in', 'content', 'write', '--uri', send_key.URI + name, input=payload)
    # exec-in need not return the remote command status: verify the provider's receipt as send_key.py does.
    after = send_key.settled(name, lambda value: bool(value) and value != before)
    if not after or after == before:
        # Android 9 sometimes drops exec-in's input; use the same verified fallback as send_key.py.
        send_key.adb('shell', 'content', 'write', '--uri', send_key.URI + name, input=payload)
        after = send_key.settled(name, lambda value: bool(value) and value != before)
    if not after or after == before:
        raise ValueError('The TV did not confirm the connection. Check that the updated APK is installed.')
    print(f"Saved {user['Name']} and {session.get('DeviceName', 'TV')} ({count} channels). The API key was not displayed.")
    print('Press Home to reload. In Jellyfin → Authentication → Automatic sign in, select this account to skip the profile chooser.')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        # Error messages never include a request header or the key.
        print(f'Setup failed: {error}', file=sys.stderr)
        sys.exit(1)
