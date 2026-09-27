#!/bin/bash

# Compile Minecraft Cross-Subnet LAN Multicast Utility
# Usage: ./compile.sh

JAVA_SOURCE_DIR="src/main/java"
OUTPUT_DIR="bin"
LIB_DIR="lib"

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

echo "Compiling Java source files..."

# Compile the main utility class
javac -d "$OUTPUT_DIR" \
  -cp "$LIB_DIR/*:$OUTPUT_DIR" \
  "$JAVA_SOURCE_DIR/lava/LanBroadcastUtil.java" \
  "$JAVA_SOURCE_DIR/lava/LanBroadcastUtil$1.java"

if [ $? -eq 0 ]; then
  echo "✓ Compilation successful!"
  echo "✓ Output directory: $OUTPUT_DIR"
  echo ""
  echo "To run the multicast announcer:"
  echo "  java -cp $OUTPUT_DIR lava.LanBroadcastUtil"
else
  echo "✗ Compilation failed!"
  exit 1
fi
