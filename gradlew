#!/usr/bin/env sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

should_bootstrap_omniinfer=false
for arg in "$@" ; do
    case "$arg" in
        -*)
            ;;
        *Omniinfer*|*omniinfer*|build|:build|assemble|:assemble|check|:check|test|:test)
            should_bootstrap_omniinfer=true
            ;;
    esac
done

bootstrap_omniinfer_submodules () {
    OMNIINFER_ROOT="$APP_HOME/third_party/omniinfer"
    OMNIINFER_SERVER_MARKER="$OMNIINFER_ROOT/android/omniinfer-server/build.gradle.kts"
    OMNIINFER_MNN_MARKER="$OMNIINFER_ROOT/framework/mnn/CMakeLists.txt"
    OMNIINFER_MODEL_DOWNLOADER_MARKER="$OMNIINFER_ROOT/framework/mnn/apps/frameworks/model_downloader/android/build.gradle"
    OMNIINFER_LLAMA_MARKER="$OMNIINFER_ROOT/framework/llama.cpp/CMakeLists.txt"

    if [ -f "$OMNIINFER_SERVER_MARKER" ] \
        && [ -f "$OMNIINFER_MNN_MARKER" ] \
        && [ -f "$OMNIINFER_MODEL_DOWNLOADER_MARKER" ] \
        && [ -f "$OMNIINFER_LLAMA_MARKER" ] ; then
        return 0
    fi

    command -v git >/dev/null 2>&1 || die "ERROR: git is required to initialize OmniInfer submodules for OmniInfer builds."
    [ -e "$APP_HOME/.git" ] || die "ERROR: Missing .git metadata; cannot auto-initialize OmniInfer submodules in this checkout."

    echo "Bootstrapping required OmniInfer submodules..."

    if [ ! -f "$OMNIINFER_SERVER_MARKER" ] ; then
        git -C "$APP_HOME" submodule update --init third_party/omniinfer || die "ERROR: Failed to initialize third_party/omniinfer."
    fi

    if [ ! -f "$OMNIINFER_MNN_MARKER" ] || [ ! -f "$OMNIINFER_MODEL_DOWNLOADER_MARKER" ] || [ ! -f "$OMNIINFER_LLAMA_MARKER" ] ; then
        git -C "$OMNIINFER_ROOT" submodule update --init framework/mnn framework/llama.cpp || die "ERROR: Failed to initialize required OmniInfer nested submodules."
    fi
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

if [ "$should_bootstrap_omniinfer" = true ] ; then
    bootstrap_omniinfer_submodules
fi

# Flutter native-assets resolves NDK from environment first, then picks the
# newest sdk/ndk/<version>. If a higher version is only partially installed
# (contains only ".installer"), Flutter will fail with:
# "Android NDK Clang could not be found."
# To keep Gradle builds stable, auto-pin to the latest *complete* NDK when
# ANDROID_NDK_* is not explicitly provided by the caller.
if [ -z "$ANDROID_NDK_HOME" ] && [ -z "$ANDROID_NDK_PATH" ] && [ -z "$ANDROID_NDK_ROOT" ] ; then
    if [ -f "$APP_HOME/local.properties" ] ; then
        SDK_DIR=`sed -n 's/^sdk\.dir=//p' "$APP_HOME/local.properties" | tail -n 1`
        if [ -n "$SDK_DIR" ] ; then
            LLVM_HOST_DIR="linux-x86_64"
            if [ "$darwin" = "true" ] ; then
                # Flutter uses darwin-x86_64 for NDK toolchain lookup on macOS.
                LLVM_HOST_DIR="darwin-x86_64"
            fi
            RESOLVED_NDK_HOME=""
            for ndk_dir in "$SDK_DIR"/ndk/* ; do
                if [ ! -d "$ndk_dir" ] ; then
                    continue
                fi
                CLANG_PATH="$ndk_dir/toolchains/llvm/prebuilt/$LLVM_HOST_DIR/bin/clang"
                if [ -x "$CLANG_PATH" ] ; then
                    RESOLVED_NDK_HOME="$ndk_dir"
                fi
            done
            if [ -n "$RESOLVED_NDK_HOME" ] ; then
                export ANDROID_NDK_HOME="$RESOLVED_NDK_HOME"
                export ANDROID_NDK_PATH="$RESOLVED_NDK_HOME"
                export ANDROID_NDK_ROOT="$RESOLVED_NDK_HOME"
            fi
        fi
    fi
fi

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar


# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = "false" -a "$darwin" = "false" -a "$nonstop" = "false" ] ; then
    MAX_FD_LIMIT=`ulimit -H -n`
    if [ $? -eq 0 ] ; then
        if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then
            MAX_FD="$MAX_FD_LIMIT"
        fi
        ulimit -n $MAX_FD
        if [ $? -ne 0 ] ; then
            warn "Could not set maximum file descriptor limit: $MAX_FD"
        fi
    else
        warn "Could not query maximum file descriptor limit: $MAX_FD_LIMIT"
    fi
fi

# For Darwin, add options to specify how the application appears in the dock
if $darwin; then
    GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if [ "$cygwin" = "true" -o "$msys" = "true" ] ; then
    APP_HOME=`cygpath --path --mixed "$APP_HOME"`
    CLASSPATH=`cygpath --path --mixed "$CLASSPATH"`

    JAVACMD=`cygpath --unix "$JAVACMD"`

    # We build the pattern for arguments to be converted via cygpath
    ROOTDIRSRAW=`find -L / -maxdepth 1 -mindepth 1 -type d 2>/dev/null`
    SEP=""
    for dir in $ROOTDIRSRAW ; do
        ROOTDIRS="$ROOTDIRS$SEP$dir"
        SEP="|"
    done
    OURCYGPATTERN="(^($ROOTDIRS))"
    # Add a user-defined pattern to the cygpath arguments
    if [ "$GRADLE_CYGPATTERN" != "" ] ; then
        OURCYGPATTERN="$OURCYGPATTERN|($GRADLE_CYGPATTERN)"
    fi
    # Now convert the arguments - kludge to limit ourselves to /bin/sh
    i=0
    for arg in "$@" ; do
        CHECK=`echo "$arg"|egrep -c "$OURCYGPATTERN" -`
        CHECK2=`echo "$arg"|egrep -c "^-"`                                 ### Determine if an option

        if [ $CHECK -ne 0 ] && [ $CHECK2 -eq 0 ] ; then                    ### Added a condition
            eval `echo args$i`=`cygpath --path --ignore --mixed "$arg"`
        else
            eval `echo args$i`="\"$arg\""
        fi
        i=`expr $i + 1`
    done
    case $i in
        0) set -- ;;
        1) set -- "$args0" ;;
        2) set -- "$args0" "$args1" ;;
        3) set -- "$args0" "$args1" "$args2" ;;
        4) set -- "$args0" "$args1" "$args2" "$args3" ;;
        5) set -- "$args0" "$args1" "$args2" "$args3" "$args4" ;;
        6) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" ;;
        7) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" "$args6" ;;
        8) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" "$args6" "$args7" ;;
        9) set -- "$args0" "$args1" "$args2" "$args3" "$args4" "$args5" "$args6" "$args7" "$args8" ;;
    esac
fi

# Escape application args
save () {
    for i do printf %s\\n "$i" | sed "s/'/'\\\\''/g;1s/^/'/;\$s/\$/' \\\\/" ; done
    echo " "
}
APP_ARGS=`save "$@"`

# Collect all arguments for the java command, following the shell quoting and substitution rules
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "\"$CLASSPATH\"" org.gradle.wrapper.GradleWrapperMain "$APP_ARGS"

exec "$JAVACMD" "$@"
