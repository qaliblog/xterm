#!/bin/sh
# Guest-side initialization script ported from termos

# Fix linker warning
if [ ! -f /linkerconfig/ld.config.txt ]; then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

# Fix group warnings
if [ -f /etc/group ]; then
    for gid in 3003 9997 20609 20610 50609 50610 99909997; do
        if ! grep -q "^[^:]*:[^:]*:$gid:" /etc/group 2>/dev/null; then
            echo "android_$gid:x:$gid:" >> /etc/group 2>/dev/null || true
        fi
    done
fi

if [ "$#" -eq 0 ]; then
    [ -f /etc/profile ] && . /etc/profile
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@xterm \[\033[39m\]\w \[\033[0m\]\\$ "
    export HOME=/root
    export TERM=xterm-256color
    cd $HOME

    # Start bash if available, otherwise ash or sh
    if command -v bash >/dev/null 2>&1; then
        exec bash -l
    elif command -v ash >/dev/null 2>&1; then
        exec ash
    else
        exec sh
    fi
else
    exec "$@"
fi
