"""Renew only the ignored local test invite through the normal admin API."""
import base64
import hashlib
import hmac
import json
from pathlib import Path
import ssl
import struct
import time
import urllib.error
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        return None


def main():
    root = Path(__file__).resolve().parent.parent
    path = root / ".local/dev.env"
    lines = path.read_text(encoding="utf-8").splitlines()
    settings = dict(line.split("=", 1) for line in lines if "=" in line and not line.startswith("#"))
    context = ssl.create_default_context(cafile=str(root / ".local/dev-ca.crt"))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context), NoRedirect())
    access = None

    def request(endpoint, body, grant=None):
        headers = {"Content-Type": "application/json"}
        if access:
            headers["Authorization"] = "Bearer " + access
        if grant:
            headers["X-VYRN-Admin-Token"] = grant
        message = urllib.request.Request("https://localhost:18443" + endpoint, data=json.dumps(body).encode(), headers=headers, method="POST")
        with opener.open(message, timeout=20) as response:
            return json.load(response) if response.status != 204 else {}

    try:
        session = request("/v1/auth/login", {"email": settings["VYRN_BOOTSTRAP_EMAIL"], "password": settings["VYRN_BOOTSTRAP_PASSWORD"], "device_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "vyrn-local-test-invite")), "device_name": "Local test invite maintenance"})
        access = session["access_token"]
        secret = settings["VYRN_ADMIN_TOTP_SECRET"]
        key = base64.b32decode(secret + "=" * (-len(secret) % 8), casefold=True)
        digest = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
        offset = digest[-1] & 15
        code = f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7fffffff) % 1000000:06d}"
        grant = request("/v1/admin/unlock", {"code": code})["admin_token"]
        invite = request("/v1/admin/invites", {"uses": 100, "days": 7}, grant)["code"]
        if len(invite) != 43 or not any(line.startswith("VYRN_BOOTSTRAP_INVITE=") for line in lines):
            raise ValueError("invalid_local_invite")
        updated = "\n".join("VYRN_BOOTSTRAP_INVITE=" + invite if line.startswith("VYRN_BOOTSTRAP_INVITE=") else line for line in lines) + "\n"
        temporary = path.with_name("dev.env.invite.tmp")
        try:
            temporary.write_text(updated, encoding="utf-8")
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
        print("Local test invite renewed: 100 uses, 7 days; code stored only in ignored dev.env.")
    finally:
        if access:
            request("/v1/auth/logout", {})


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as failure:
        raise SystemExit(f"Local invite renewal failed: HTTP {failure.code}; no credentials printed.") from None
    except Exception as failure:
        raise SystemExit(f"Local invite renewal failed: {type(failure).__name__}; no credentials printed.") from None
