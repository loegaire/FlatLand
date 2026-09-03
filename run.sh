#!/usr/bin/env bash
# FlatLand launcher.
# On niri + xwayland-satellite, Java2D window buffers never reach the screen
# (windows stay white). Xephyr provides a real nested X server that works.
set -e
cd "$(dirname "$0")"

JDK=/tmp/opencode/scratch/jdk/bin
if command -v javac >/dev/null 2>&1; then JAVAC=javac; else JAVAC=$JDK/javac; fi

if [ MainGame.java -nt MainGame.class ] || [ ! -f MainGame.class ]; then
  "$JAVAC" -encoding UTF-8 *.java
fi

# reuse an existing nested X server, start one if missing
if [ ! -S /tmp/.X11-unix/X1 ]; then
  Xephyr :1 -screen 1000x700x24 >/dev/null 2>&1 &
  sleep 1.5
fi

export DISPLAY=:1
exec "${JDK}/java" MainGame
