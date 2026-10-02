# Ubuntu 24.04 PEP 668 & Default Virtual Environment

## What is PEP 668?

In modern Linux distributions (such as Ubuntu 24.04 LTS and Debian 12), PEP 668 is enforced by default. Running `pip install <package>` as root or in the global environment triggers:
```text
error: externally-managed-environment

× This environment is externally managed
╰─> To install Python packages system-wide, try apt install
    python3-xyz, where xyz is the package you are trying to
    install.

    If you wish to install a non-Debian-packaged Python package,
    create a virtual environment using python3 -m venv path/to/venv.
```

While `--break-system-packages` exists, it can corrupt system-level apt python packages and cause unexpected failures.

## RikkaHub Default Virtual Environment Architecture

To provide a seamless developer experience inside RikkaHub workspaces:
1. **Dedicated Path**: A persistent virtual environment is created at `/workspace/.venv/default`.
2. **Auto-activation**:
   In `~/.bashrc` and `/etc/profile.d/rikka_default_venv.sh`:
   ```bash
   # >>> rikkahub default venv >>>
   if [ -d "/workspace/.venv/default" ] && [ -f "/workspace/.venv/default/bin/activate" ]; then
       . /workspace/.venv/default/bin/activate
   fi
   # <<< rikkahub default venv <<<
   ```
3. **Pip Mirror Configuration**:
   ```bash
   /workspace/.venv/default/bin/pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple
   ```
4. **Result**: Both interactive users in the terminal and AI tool executions (`workspace_shell`) can run `pip install <package>` directly without PEP 668 errors or manual `source` commands.
