#!/bin/sh
# RikkaHub Workspace Environment Setup - Python Virtual Environment (PEP 668 bypass)
# Sets up /workspace/.venv/default and injects auto-activation into ~/.bashrc

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() {
    printf "${GREEN}[INFO]${NC} %s\n" "$1"
}

log_warn() {
    printf "${YELLOW}[WARN]${NC} %s\n" "$1"
}

log_err() {
    printf "${RED}[ERROR]${NC} %s\n" "$1"
}

# 1. 确保安装 python3 与 venv 工具包
if ! command -v python3 >/dev/null 2>&1; then
    log_warn "python3 not found. Attempting installation..."
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -y
        apt-get install -y --no-install-recommends python3 python3-venv python3-pip
    elif command -v apk >/dev/null 2>&1; then
        apk update
        apk add --no-cache python3 py3-pip py3-virtualenv
    else
        log_err "Unsupported package manager. Please install python3 manually."
        exit 1
    fi
fi

# 检查 venv 模块是否可用 (Ubuntu/Debian 常常单独拆分了 python3-venv)
if ! python3 -m venv --help >/dev/null 2>&1; then
    log_warn "python3-venv module missing. Installing python3-venv..."
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -y
        apt-get install -y --no-install-recommends python3-venv python3-pip
    fi
fi

# 2. 创建工作区虚拟环境 /workspace/.venv/default
VENV_DIR="/workspace/.venv/default"
mkdir -p /workspace/.venv

if [ ! -d "$VENV_DIR" ] || [ ! -f "$VENV_DIR/bin/activate" ]; then
    log_info "Creating default virtual environment at $VENV_DIR..."
    python3 -m venv "$VENV_DIR"
else
    log_info "Default virtual environment already exists at $VENV_DIR."
fi

# 3. 配置 pip 清华镜像源
log_info "Configuring Tsinghua PyPI mirror..."
"$VENV_DIR/bin/pip" config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple
"$VENV_DIR/bin/pip" config set global.trusted-host pypi.tuna.tsinghua.edu.cn

# 4. 自动激活注入 (~/.bashrc)
BASHRC="$HOME/.bashrc"
PROFILE_D="/etc/profile.d/rikka_default_venv.sh"

INJECTION_BLOCK=$(cat << 'INJECT_EOF'
# >>> rikkahub default venv >>>
if [ -d "/workspace/.venv/default" ] && [ -f "/workspace/.venv/default/bin/activate" ]; then
    . /workspace/.venv/default/bin/activate
fi
# <<< rikkahub default venv <<<
INJECT_EOF
)

if [ -f "$BASHRC" ]; then
    if ! grep -q "rikkahub default venv" "$BASHRC"; then
        log_info "Injecting auto-activation into $BASHRC..."
        printf "\n%s\n" "$INJECTION_BLOCK" >> "$BASHRC"
    else
        log_info "Auto-activation already present in $BASHRC."
    fi
else
    log_info "Creating $BASHRC with auto-activation..."
    printf "%s\n" "$INJECTION_BLOCK" > "$BASHRC"
fi

# 如果存在 /etc/profile.d，写入系统级自启脚本以便各种 shell 登录继承
if [ -d "/etc/profile.d" ]; then
    printf "%s\n" "$INJECTION_BLOCK" > "$PROFILE_D"
    chmod +x "$PROFILE_D" 2>/dev/null || true
fi

log_info "Python environment setup complete!"
log_info "Virtual environment: $VENV_DIR"
log_info "Python version: $("$VENV_DIR/bin/python" --version)"
log_info "Pip index: $("$VENV_DIR/bin/pip" config get global.index-url)"
log_info "You can now run 'pip install <package>' directly in any workspace session."
