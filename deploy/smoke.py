#!/usr/bin/env python3
"""Read and write acceptance check. Temporary records are deleted even on failure."""
import argparse
import http.cookiejar
import json
import os
import urllib.request
import uuid


def check(base, attachments=True, user=None, password=None):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def request(method, path, body=None, content_type='application/json'):
        data = json.dumps(body).encode() if body is not None and content_type == 'application/json' else body
        req = urllib.request.Request(base + path, data=data, method=method, headers={'Content-Type': content_type})
        with opener.open(req, timeout=40) as response:
            raw = response.read()
            if response.headers.get_content_type() == 'application/json':
                return json.loads(raw)
            return raw

    health = request('GET', '/api/health')
    assert health['status'] == 'ok', health
    assert b'<html' in request('GET', '/').lower()
    user = user or os.environ.get('PONTO_WEB_USER')
    password = password or os.environ.get('PONTO_WEB_PASSWORD')
    if health.get('auth') or (user and password):
        assert user and password, 'PONTO_WEB_USER and PONTO_WEB_PASSWORD required when auth is enabled'
        request('POST', '/api/login', {'user': user, 'password': password})
        assert request('GET', '/api/me')['user'] == user
    companies = request('GET', '/api/companies')
    default = companies['activeId']
    company = note = entry = None
    try:
        company = request('POST', '/api/companies?companyId=' + str(default), {'name': '__deploy_check_' + uuid.uuid4().hex})['id']
        suffix = '?companyId=' + str(company)
        entry = request('POST', '/api/entries' + suffix,
                        {'dia': '2026-05-20', 'tin': '08:00', 'tout': '16:00', 'just': '__deploy_check'})['id']
        note = request('POST', '/api/notes' + suffix, {'title': '__deploy_check', 'body': 'verificação'})['id']
        request('PUT', '/api/notes/' + str(note) + suffix, {'title': '__deploy_check', 'body': 'salvo', 'revision': 1})
        assert request('GET', '/api/notes/' + str(note) + suffix)['body'] == 'salvo'
        assert request('GET', '/api/sum' + suffix + '&from=2026-05-20&to=2026-05-20')['total'] == '8:00'
        assert request('GET', '/api/entries' + suffix)['total'] == 1
        for kind, magic in [('pdf', b'%PDF'), ('xls', bytes.fromhex('D0CF11E0')), ('csv', b'\xef\xbb\xbf')]:
            raw = request('GET', '/api/export/' + kind + suffix + '&from=2026-05-20&to=2026-05-20')
            assert raw.startswith(magic), kind
        if attachments:
            boundary = 'ponto' + uuid.uuid4().hex
            raw = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="check.txt"\r\n'
                   'Content-Type: text/plain\r\n\r\nponto-check\r\n--' + boundary + '--\r\n').encode()
            attachment = request('POST', '/api/notes/' + str(note) + '/files' + suffix, raw, 'multipart/form-data; boundary=' + boundary)
            assert request('GET', '/api/notes/' + str(note) + '/files/' + str(attachment['id']) + '/download' + suffix) == b'ponto-check'
        assert request('GET', '/api/companies')['activeId'] == default
        return health
    finally:
        if company:
            suffix = '?companyId=' + str(company)
            # Querying the temporary company also cleans rows created before an interrupted response.
            for n in request('GET', '/api/notes' + suffix)['notes']:
                request('DELETE', '/api/notes/' + str(n['id']) + suffix)
            for e in request('GET', '/api/entries' + suffix)['entries']:
                request('DELETE', '/api/entries/' + str(e['id']) + suffix)
            request('DELETE', '/api/companies/' + str(company) + '?companyId=' + str(default))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8090')
    parser.add_argument('--without-storage', action='store_true')
    parser.add_argument('--user', default=None)
    parser.add_argument('--password', default=None)
    args = parser.parse_args()
    print(json.dumps(check(args.url, not args.without_storage, args.user, args.password)))
