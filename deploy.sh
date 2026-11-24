#!/bin/bash
# Deployment script for Cosmic Live Studio
# Run this on your HOST machine (not in the container)
# Usage: ./deploy.sh [github_username]

set -e

PROJECT_DIR="/storage/emulated/0/Documents/Cosmic Live Studio"
GITHUB_USER="${1:-}"

cd "$PROJECT_DIR"

echo "=== Cosmic Live Studio Deployment ==="
echo "Project directory: $PROJECT_DIR"
echo ""

# Check if gh is authenticated
if ! gh auth status >/dev/null 2>&1; then
    echo "❌ GitHub CLI not authenticated. Run: gh auth login"
    exit 1
fi

echo "✅ GitHub CLI authenticated"

# Initialize git if needed
if [ ! -d .git ]; then
    echo "Initializing git repository..."
    git init
    git config user.email "andikadwipradita@github.com"
    git config user.name "AndikaDwiPradita"
    git add .
    git commit -m "Initial commit: Cosmic Live Studio Android project

- MainActivity with role selection (Sender/Receiver)
- SenderActivity with MediaProjection screen capture
- H264ScreenEncoder for hardware encoding
- ScreenCaptureService as foreground service
- UdpBeaconBroadcaster for auto-discovery
- ReceiverActivity with SurfaceView rendering
- H264StreamDecoder for hardware decoding
- UdpPairingResolver for 6-digit code pairing
- StreamProtocol for framed H.264 packet handling
- GitHub Actions workflow for cloud build

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
    git branch -m main
    echo "✅ Git repository initialized"
else
    echo "✅ Git repository already exists"
fi

# Create GitHub repository
REPO_NAME="cosmic-live-studio"
if [ -n "$GITHUB_USER" ]; then
    REPO_FULL="$GITHUB_USER/$REPO_NAME"
else
    REPO_FULL="$REPO_NAME"
fi

echo "Creating GitHub repository: $REPO_FULL"
if gh repo view "$REPO_FULL" >/dev/null 2>&1; then
    echo "Repository already exists"
else
    gh repo create "$REPO_FULL" --private --source=. --push
    echo "✅ Repository created and pushed"
fi

# Wait for workflow to complete
echo ""
echo "=== Monitoring GitHub Actions Workflow ==="
echo "Waiting for build to complete..."

# Get the latest workflow run
RUN_ID=$(gh run list --repo "$REPO_FULL" --workflow "Build Debug APK" --limit 1 --json databaseId --jq '.[0].databaseId')

if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
    echo "⚠️  No workflow run found. Triggering manually..."
    gh workflow run "Build Debug APK" --repo "$REPO_FULL"
    sleep 5
    RUN_ID=$(gh run list --repo "$REPO_FULL" --workflow "Build Debug APK" --limit 1 --json databaseId --jq '.[0].databaseId')
fi

echo "Workflow run ID: $RUN_ID"

# Monitor workflow
while true; do
    STATUS=$(gh run view "$RUN_ID" --repo "$REPO_FULL" --json status --jq '.status')
    CONCLUSION=$(gh run view "$RUN_ID" --repo "$REPO_FULL" --json conclusion --jq '.conclusion // "null"')
    
    echo "Status: $STATUS, Conclusion: $CONCLUSION"
    
    if [ "$STATUS" = "completed" ]; then
        if [ "$CONCLUSION" = "success" ]; then
            echo "✅ Build successful!"
            break
        else
            echo "❌ Build failed!"
            gh run view "$RUN_ID" --repo "$REPO_FULL" --log-failed
            exit 1
        fi
    fi
    
    sleep 10
done

# Download artifact
echo ""
echo "=== Downloading APK ==="
mkdir -p release
gh run download "$RUN_ID" --repo "$REPO_FULL" --name "Cosmic-Live-Studio-Debug-APK" --dir release/

# Find and rename APK
APK_FILE=$(find release -name "*.apk" | head -1)
if [ -n "$APK_FILE" ]; then
    mv "$APK_FILE" "release/app-debug.apk"
    echo "✅ APK downloaded to: release/app-debug.apk"
    ls -la release/app-debug.apk
else
    echo "❌ APK not found in artifact"
    exit 1
fi

echo ""
echo "=== Deployment Complete ==="
echo "APK location: $PROJECT_DIR/release/app-debug.apk"
echo ""
echo "Install on device:"
echo "  adb install -r $PROJECT_DIR/release/app-debug.apk"
