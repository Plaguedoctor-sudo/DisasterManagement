#!/usr/bin/env python3
"""
Zero-Infrastructure Offline Wi-Fi Hotspot Distribution Server.
Runs an HTTP server on port 8080 serving the signed APK and START_HERE.html.
No internet required.
"""

import http.server
import socket
import socketserver
import os
import sys

PORT = 8080

def get_ip_addresses():
    ips = []
    try:
        # Get all local interfaces
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    
    # Try connecting to a dummy IP to get the active default route interface
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        primary = s.getsockname()[0]
        s.close()
        if primary not in ips and not primary.startswith("127."):
            ips.insert(0, primary)
    except Exception:
        pass

    if not ips:
        ips = ["127.0.0.1"]
    return ips

def main():
    package_dir = os.path.dirname(os.path.abspath(__file__))
    os.chdir(package_dir)

    print("=" * 68)
    print("🛡️  DisasterManagement Zero-Infrastructure Hotspot Server")
    print("=" * 68)
    print(f"Directory: {package_dir}")
    print(f"Serving on Port: {PORT}")
    print("\nConnect civilian smartphones to this machine's Wi-Fi hotspot,")
    print("then open their mobile web browser to:")
    
    for ip in get_ip_addresses():
        print(f"   👉 http://{ip}:{PORT}/START_HERE.html")
        print(f"      (Direct APK: http://{ip}:{PORT}/DisasterManagement-v1.2.0-signed.apk)")
    
    print("\nPress Ctrl+C to stop the server.")
    print("=" * 68)

    class CustomHandler(http.server.SimpleHTTPRequestHandler):
        def end_headers(self):
            # Add STIG-like security headers
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
            super().end_headers()

    socketserver.TCPServer.allow_reuse_address = True
    try:
        with socketserver.TCPServer(("", PORT), CustomHandler) as httpd:
            httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[!] Server stopped by operator.")
    except Exception as e:
        print(f"\n[❌] Error starting server: {e}")

if __name__ == "__main__":
    main()
