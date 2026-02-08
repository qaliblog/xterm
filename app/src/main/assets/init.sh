#!/bin/sh
# Initial script running inside the guest environment

# Setup basic environment if not already set
export HOME=/root
export TERM=xterm-256color
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin
export USER=root
export TMPDIR=/tmp
export LANG=C.UTF-8

# Fix group warnings
if [ -f /etc/group ]; then
    for gid in 3003 9997 20609 20610 50609 50610 99909997; do
        if ! grep -q "^[^:]*:[^:]*:$gid:" /etc/group 2>/dev/null; then
            echo "android_$gid:x:$gid:" >> /etc/group 2>/dev/null || true
        fi
    done
fi

# Set a nice prompt if we are in a login shell and no command is provided
if [ "$#" -eq 0 ]; then
    [ -f /etc/profile ] && . /etc/profile
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@xterm \[\033[39m\]\w \[\033[0m\]\\$ "
    cd $HOME

    # Try to start a better shell if available
    if [ -x /bin/bash ]; then
        exec /bin/bash -l
    elif [ -x /usr/bin/bash ]; then
        exec /usr/bin/bash -l
    else
        exec /bin/sh
    fi
else
    # Execute the command passed to the script
    exec "$@"
fi
