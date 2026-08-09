{
  description = "Bilingual Keyboard — the toolchain and the steps CI runs, on your machine";

  # The workflows in .github/workflows pin their toolchain by action:
  # temurin JDK 17, android-actions/setup-android for the SDK, and the Gradle
  # wrapper (8.14.3) for Gradle itself. This flake pins the same three things
  # from nixpkgs, and the apps below run the same gradle tasks in the same
  # order, so a green `nix run .#ci` means the same thing a green Android
  # workflow does.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];

      # The Android SDK is unfree and its licences have to be accepted before
      # nixpkgs will build it. Setting both here rather than expecting them in
      # the user's nixpkgs config keeps `nix run` working on a fresh machine.
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      }));

      # Both track app/build.gradle.kts: compileSdk/targetSdk 35, and
      # build-tools 35 for aapt2 and for the apksigner the release job calls.
      platformVersion = "35";
      buildToolsVersion = "35.0.0";

      toolchain = pkgs: rec {
        # setup-java@v4 with distribution: temurin, java-version: 17.
        jdk = pkgs.temurin-bin-17;

        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ platformVersion ];
          buildToolsVersions = [ buildToolsVersion ];
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
          includeNDK = false;
        };

        sdkRoot = "${android.androidsdk}/libexec/android-sdk";

        # AGP downloads its own aapt2 from Maven and runs the binary directly.
        # That binary is linked against an interpreter NixOS does not have, so
        # it dies with "no such file or directory" on a file that plainly
        # exists. Point AGP at the SDK's aapt2, which is already patched.
        aapt2 = "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

        environment = {
          JAVA_HOME = "${jdk}";
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";
        };

        # `nix run` hands us the caller's directory, not the flake source — the
        # store copy is read-only and Gradle wants to write build/. So every app
        # starts by finding the checkout it was invoked from.
        preamble = ''
          root=$PWD
          while [ ! -f "$root/settings.gradle.kts" ]; do
            parent=$(dirname "$root")
            if [ "$parent" = "$root" ]; then
              echo "not inside a bilingual-android-keyboard checkout" >&2
              exit 1
            fi
            root=$parent
          done
          cd "$root" || exit 1

          export JAVA_HOME=${environment.JAVA_HOME}
          export ANDROID_HOME=${environment.ANDROID_HOME}
          export ANDROID_SDK_ROOT=${environment.ANDROID_SDK_ROOT}
          export GRADLE_OPTS=${pkgs.lib.escapeShellArg environment.GRADLE_OPTS}
          export PATH="$JAVA_HOME/bin:$PATH"
        '';

        # The wrapper downloads Gradle 8.14.3 on first use, exactly as
        # gradle/actions/setup-gradle does on the runner, so the Gradle version
        # is the one the repository pins rather than whatever nixpkgs carries.
        app = name: text: {
          type = "app";
          program = "${pkgs.writeShellApplication {
            inherit name;
            runtimeInputs = [
              jdk
              android.androidsdk
              pkgs.git
              pkgs.coreutils
              pkgs.findutils
              pkgs.gnugrep
            ];
            text = preamble + text;
          }}/bin/${name}";
        };
      };
    in
    {
      packages = forAllSystems (pkgs:
        let tc = toolchain pkgs; in {
          # `nix build .#android-sdk` gives a plain SDK tree, which is also what
          # local.properties would point at if you would rather not use the shell.
          android-sdk = tc.android.androidsdk;
          jdk = tc.jdk;
          default = tc.android.androidsdk;
        });

      devShells = forAllSystems (pkgs:
        let tc = toolchain pkgs; in {
          default = pkgs.mkShell (tc.environment // {
            packages = [ tc.jdk tc.android.androidsdk pkgs.git ];

            shellHook = ''
              echo "JDK          $(java -version 2>&1 | head -n1)"
              echo "Android SDK  ${tc.sdkRoot}"
              echo "Gradle       from ./gradlew (8.14.3, downloaded on first use)"
              echo
              echo "CI, in order: ./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace"
            '';
          });
        });

      apps = forAllSystems (pkgs:
        let
          tc = toolchain pkgs;

          # The three steps of the Android workflow's build job, verbatim.
          checks = ''
            ./gradlew testDebugUnitTest --stacktrace
            ./gradlew lintDebug --stacktrace
          '';

          ci = tc.app "ci" ''
            ${checks}
            ./gradlew assembleDebug --stacktrace

            # "Name the APK after the build", with git standing in for GITHUB_SHA.
            src=$(find app/build/outputs/apk/debug -name '*.apk' | head -n1)
            name="bilingual-keyboard-$(git rev-parse --short=7 HEAD).apk"
            mkdir -p artifacts
            cp "$src" "artifacts/$name"
            echo
            echo "artifacts/$name"
          '';
        in
        {
          inherit ci;
          default = ci;

          test = tc.app "test" ''
            ./gradlew testDebugUnitTest --stacktrace
          '';

          lint = tc.app "lint" ''
            ./gradlew lintDebug --stacktrace
          '';

          # The release workflow, minus the parts that only GitHub can do
          # (decoding the keystore secret, creating the release). Without the
          # four RELEASE_* variables this signs with the committed debug key,
          # which is what the workflow falls back to as well.
          #
          #     nix run .#release -- 0.2.0
          release = tc.app "release" ''
            version=''${1:-}
            if ! printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
              echo "usage: nix run .#release -- <major.minor.patch>" >&2
              exit 1
            fi
            IFS=. read -r major minor patch <<< "$version"

            BIKEYBOARD_VERSION_NAME="$version"
            BIKEYBOARD_VERSION_CODE=$(( major * 10000 + minor * 100 + patch ))
            export BIKEYBOARD_VERSION_NAME BIKEYBOARD_VERSION_CODE
            echo "Building $version (versionCode $BIKEYBOARD_VERSION_CODE)"

            ${checks}
            ./gradlew assembleRelease --stacktrace

            src=$(find app/build/outputs/apk/release -name '*.apk' | head -n1)
            name="bilingual-keyboard-$version.apk"
            mkdir -p artifacts
            cp "$src" "artifacts/$name"

            # Same reason as in the workflow: an APK that assembles and cannot
            # be installed is the one failure a green build does not catch.
            apksigner=$(find "$ANDROID_SDK_ROOT/build-tools" -name apksigner -type f | sort -V | tail -n1)
            "$apksigner" verify --print-certs "artifacts/$name"
            sha256sum "artifacts/$name"
          '';
        });
    };
}
