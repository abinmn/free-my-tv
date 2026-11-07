"""Setup tests use only a local fixture server; they never contact or control a real TV."""
import http.server
import json
from pathlib import Path
import sys
import threading
import unittest
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import setup_jellyfin


class Fixture(http.server.BaseHTTPRequestHandler):
    target_called = False

    def do_GET(self):
        if self.path == '/redirect':
            self.send_response(302)
            self.send_header('Location', '/target')
            self.end_headers()
            return
        if self.path == '/target':
            Fixture.target_called = True
        self.send_response(200)
        self.end_headers()
        if self.path == '/large':
            self.wfile.write(b'x' * 4_000_001)
        else:
            self.wfile.write(json.dumps({'authorization': self.headers.get('Authorization')}).encode())

    def log_message(self, *args):
        pass


class SetupTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.api = setup_jellyfin.Jellyfin(f'http://127.0.0.1:{cls.server.server_port}', 'fixture-key-only')

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_authenticated_header_without_key_in_url(self):
        result = self.api.get('/info')
        self.assertEqual(result['authorization'], 'MediaBrowser Token="fixture-key-only"')

    def test_redirect_does_not_forward_credentials(self):
        Fixture.target_called = False
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.api.get('/redirect')
        self.assertEqual(error.exception.code, 302)
        self.assertFalse(Fixture.target_called)

    def test_response_size_is_bounded(self):
        with self.assertRaisesRegex(ValueError, 'too large'):
            self.api.get('/large')

    def test_private_http_and_https(self):
        for address in ['http://10.0.0.2:8096/', 'http://172.16.0.2:8096', 'http://192.168.2.3:8096',
                        'http://jellyfin.local:8096', 'https://media.example.org/jellyfin/']:
            self.assertEqual(setup_jellyfin.validate_server(address), address.rstrip('/'))

    def test_public_http_and_embedded_credentials_are_refused(self):
        for address in ['http://8.8.8.8:8096', 'http://172.32.0.1', 'http://100.100.1.2',
                        'http://media.example.org', 'https://user:secret@example.org',
                        'https://example.org?key=bad', 'https://example.org/#bad', 'file:///tmp/a']:
            with self.subTest(address=address), self.assertRaises(ValueError):
                setup_jellyfin.validate_server(address)

    def test_connection_is_bound_to_the_selected_user_and_device(self):
        user = {'Id': 'a' * 32}
        session = {'UserId': 'a' * 32, 'DeviceId': 'fixture device'}
        payload = setup_jellyfin.connection('fixture-key-only', user, session)
        self.assertNotIn(b' ', payload)
        decoded = json.loads(payload)
        self.assertEqual(decoded, {'token': 'fixture-key-only', 'userId': 'a' * 32, 'deviceId': 'fixture device'})
        with self.assertRaisesRegex(ValueError, 'selected user'):
            setup_jellyfin.connection('fixture-key-only', user, {'UserId': 'b' * 32, 'DeviceId': 'other'})

    def test_invalid_key_cannot_inject_headers(self):
        with self.assertRaises(ValueError):
            setup_jellyfin.Jellyfin('https://example.org', 'bad\r\nHeader: injected')


if __name__ == '__main__':
    unittest.main()
