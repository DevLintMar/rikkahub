# Tsinghua (TUNA) Mirrors Guide & CA Bootstrap Deadlock

## The CA Bootstrap Deadlock Problem

When a fresh minimal rootfs (like Ubuntu 22.04/24.04, Debian, or Alpine) is initialized in a mobile PRoot sandbox:
1. The container does NOT have `ca-certificates` installed (`/etc/ssl/certs/ca-certificates.crt` is missing or empty).
2. If you directly switch repositories to an HTTPS mirror (`https://mirrors.tuna.tsinghua.edu.cn`), package managers (`apt`, `apk`) will fail with SSL/TLS verification errors:
   - `Certificate verification failed: The certificate is not trusted`
   - `SSL: no alternative certificate subject name matches target host name`
3. Because the package manager cannot connect to the mirror without certificates, you cannot run `apt install ca-certificates`. This is a circular deadlock.

## The Three-Step Safe Switch Solution

1. **Step 1 (Plain HTTP)**: Switch package sources to `http://mirrors.tuna.tsinghua.edu.cn/...` (plain HTTP does not perform TLS certificate validation).
2. **Step 2 (Bootstrap CA)**: Run `apt-get update && apt-get install -y ca-certificates curl` (or `apk update && apk add ca-certificates curl`). Now the system has a valid certificate trust store.
3. **Step 3 (Upgrade to HTTPS)**: Switch the repository URLs from `http://` to `https://mirrors.tuna.tsinghua.edu.cn/...` and run update again.

## Distribution Formats & Architecture Paths

### 1. Ubuntu Architecture Paths
- **arm64 / aarch64**: Ubuntu official mirrors use `ubuntu-ports/` (e.g. `http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/`).
- **x86_64 / amd64**: Ubuntu uses `ubuntu/` (e.g. `http://mirrors.tuna.tsinghua.edu.cn/ubuntu/`).
- *Warning*: Do not replace `ubuntu-ports` with `ubuntu` on an arm64 Android device, otherwise APT will return 404 Not Found.

### 2. Ubuntu 24.04 (Noble) DEB822 Format
File: `/etc/apt/sources.list.d/ubuntu.sources`
Format:
```text
Types: deb
URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
Suites: noble noble-updates noble-backports
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
```

### 3. Ubuntu 22.04 and Earlier / Debian Traditional Format
File: `/etc/apt/sources.list`
```text
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy-updates main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy-backports main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy-security main restricted universe multiverse
```

### 4. Alpine Linux Format
File: `/etc/apk/repositories`
```text
https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.19/main
https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.19/community
```
