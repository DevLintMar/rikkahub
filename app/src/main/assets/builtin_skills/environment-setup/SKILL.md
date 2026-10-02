---
name: environment-setup
description: >-
  Analyze and configure Linux workspace environment in RikkaHub, including switching package sources to Tsinghua (TUNA) mirrors for Ubuntu, Debian, or Alpine, resolving CA certificate bootstrap deadlocks via the safe three-step method, and setting up the default Python virtual environment to bypass Ubuntu 24.04 PEP 668 restrictions. Use when the user wants to switch mirrors (换源), speed up downloads (加速), fix network or SSL certificate errors in workspace, or configure Python and pip environments.
compatibility: Requires a Linux workspace (Ubuntu, Debian, or Alpine)
---

# Workspace Environment Setup

Configure and optimize the Linux container workspace in RikkaHub for domestic Chinese networks and modern Linux distributions (such as Ubuntu 24.04).

## Core Principles

1. **Intent-First, Zero Overseas Probing**: Whenever the user asks to switch package sources (换源), speed up downloads (加速), or fix workspace package manager failures, immediately switch to Tsinghua (TUNA) mirrors. Do NOT waste time probing overseas network latency (`curl archive.ubuntu.com`), which often suffers high packet loss or timeouts.
2. **Three-Step Safe Switch**: Never switch directly to an HTTPS mirror on a minimal rootfs lacking CA certificates. Always follow:
   - Step 1: Switch to plain `http://` Tsinghua mirror.
   - Step 2: Update package cache and install `ca-certificates curl`.
   - Step 3: Upgrade mirror URLs to secure `https://` Tsinghua mirror.
3. **Architecture & Format Awareness**:
   - On **arm64 / aarch64** devices (standard Android phones), Ubuntu mirrors use `ubuntu-ports/`. Do NOT use `ubuntu/`.
   - On **Ubuntu 24.04+ (Noble)**, APT uses DEB822 format (`/etc/apt/sources.list.d/ubuntu.sources`), not `/etc/apt/sources.list`.
4. **Bypass PEP 668 via Default Venv**: Modern Ubuntu blocks global `pip install`. Fix this by provisioning `/workspace/.venv/default` with auto-activation in `~/.bashrc`.

## Automated One-Click Scripts

Built-in executable helper scripts are available in `/builtin_skills/environment-setup/scripts/`:

- **Full Mirror Switch**:
  ```bash
  /builtin_skills/environment-setup/scripts/setup_tuna.sh
  ```
  Automatically detects OS (Ubuntu, Debian, Alpine), architecture (arm64/x86_64), format (DEB822/traditional), and executes the three-step safe switch.

- **Python & PEP 668 Venv Setup**:
  ```bash
  /builtin_skills/environment-setup/scripts/setup_venv.sh
  ```
  Installs python3-venv, provisions `/workspace/.venv/default`, configures Tsinghua PyPI mirror, and injects silent auto-activation into `~/.bashrc`.

- **Diagnostics**:
  ```bash
  /builtin_skills/environment-setup/scripts/diagnose.sh
  ```
  Inspects OS, current mirrors, CA certificates, Python venv, and disk space.

## Manual Step-by-Step Instructions

If scripts cannot be executed directly, perform the following steps using `workspace_shell`:

### 1. Ubuntu Package Mirror Setup

Refer to [Tsinghua Mirrors Guide](references/tuna_mirrors.md) for complete details.

1. Check architecture:
   ```bash
   ARCH=$(uname -m) # aarch64 -> ubuntu-ports, x86_64 -> ubuntu
   ```
2. For Ubuntu 24.04 (`/etc/apt/sources.list.d/ubuntu.sources`):
   ```bash
   # Step 1: Plain HTTP
   sed -i -E "s|URIs: https?://[^/]+/ubuntu/?|URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/|g" /etc/apt/sources.list.d/ubuntu.sources
   sed -i -E "s|URIs: https?://[^/]+/ubuntu-ports/?|URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/|g" /etc/apt/sources.list.d/ubuntu.sources
   # Step 2: Install CA certificates
   apt-get update -y && apt-get install -y --no-install-recommends ca-certificates curl
   # Step 3: Upgrade to HTTPS
   sed -i -E "s|URIs: http://mirrors.tuna.tsinghua.edu.cn/|URIs: https://mirrors.tuna.tsinghua.edu.cn/|g" /etc/apt/sources.list.d/ubuntu.sources
   apt-get update -y
   ```
3. For Ubuntu 22.04 or earlier (`/etc/apt/sources.list`):
   ```bash
   # Step 1: Plain HTTP
   sed -i -E "s|https?://[a-zA-Z0-9._-]+/ubuntu-ports/?|http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/|g" /etc/apt/sources.list
   # Step 2: Install CA certificates
   apt-get update -y && apt-get install -y --no-install-recommends ca-certificates curl
   # Step 3: Upgrade to HTTPS
   sed -i -E "s|http://mirrors.tuna.tsinghua.edu.cn/|https://mirrors.tuna.tsinghua.edu.cn/|g" /etc/apt/sources.list
   apt-get update -y
   ```

### 2. Python Virtual Environment Setup (PEP 668)

Refer to [PEP 668 & Default Venv Guide](references/pep668_venv.md) for background.

1. Ensure `python3` and `python3-venv` are installed:
   ```bash
   apt-get update -y && apt-get install -y --no-install-recommends python3 python3-venv python3-pip
   ```
2. Create `/workspace/.venv/default`:
   ```bash
   mkdir -p /workspace/.venv
   python3 -m venv /workspace/.venv/default
   ```
3. Configure Tsinghua PyPI mirror:
   ```bash
   /workspace/.venv/default/bin/pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple
   /workspace/.venv/default/bin/pip config set global.trusted-host pypi.tuna.tsinghua.edu.cn
   ```
4. Inject auto-activation into `~/.bashrc`:
   ```bash
   if ! grep -q "rikkahub default venv" "$HOME/.bashrc" 2>/dev/null; then
       cat << 'EOF' >> "$HOME/.bashrc"

   # >>> rikkahub default venv >>>
   if [ -d "/workspace/.venv/default" ] && [ -f "/workspace/.venv/default/bin/activate" ]; then
       . /workspace/.venv/default/bin/activate
   fi
   # <<< rikkahub default venv <<<
   EOF
   fi
   ```
5. Confirm installation by running `pip --version` inside the activated venv.
