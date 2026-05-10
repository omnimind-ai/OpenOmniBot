set -e  # Exit immediately on Failure

export PATH=/root/.npm-global/bin:/root/.local/bin:/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root
HEADLESS_MODE="${OMNIBOT_HEADLESS:-0}"

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

configure_apk_repositories() {
    if [ -z "$OMNIBOT_ALPINE_APK_REPOSITORY_BASE" ]; then
        return 0
    fi

    branch="$OMNIBOT_ALPINE_APK_BRANCH"
    if [ -z "$branch" ] && [ -r /etc/alpine-release ]; then
        branch="v$(cut -d. -f1,2 /etc/alpine-release)"
    fi
    if [ -z "$branch" ]; then
        branch="v3.21"
    fi

    mkdir -p /etc/apk
    printf '%s/%s/main\n%s/%s/community\n' \
        "$OMNIBOT_ALPINE_APK_REPOSITORY_BASE" "$branch" \
        "$OMNIBOT_ALPINE_APK_REPOSITORY_BASE" "$branch" \
        > /etc/apk/repositories
}

configure_apk_repositories || true

if [ "$HEADLESS_MODE" = "1" ]; then
    export PS1=""
    export PS2=""
    unset PROMPT_COMMAND
    export PAGER=cat
    export GIT_PAGER=cat
else
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
fi
# shellcheck disable=SC2034
export PIP_BREAK_SYSTEM_PACKAGES=1
required_packages="bash gcompat glib nano"
missing_packages=""
for pkg in $required_packages; do
    if ! apk info -e $pkg >/dev/null 2>&1; then
        missing_packages="$missing_packages $pkg"
    fi
done
if [ -n "$missing_packages" ] && [ "$HEADLESS_MODE" != "1" ]; then
    echo -e "\e[34;1m[*] \e[0mInstalling Important packages\e[0m"
    apk update && apk upgrade
    apk add $missing_packages
    if [ $? -eq 0 ]; then
        echo -e "\e[32;1m[+] \e[0mSuccessfully Installed\e[0m"
    fi
    echo -e "\e[34m[*] \e[0mUse \e[32mapk\e[0m to install new packages\e[0m"
fi

#fix linker warning
if [[ ! -f /linkerconfig/ld.config.txt ]];then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

if [ "$#" -eq 0 ]; then
    if [ "$HEADLESS_MODE" = "1" ]; then
        stty -echo -echoctl 2>/dev/null || true
        cd "${OMNIBOT_SESSION_CWD:-$HOME}"
        exec /bin/ash
    fi
    source /etc/profile
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
    cd $HOME
    /bin/ash
else
    exec "$@"
fi
