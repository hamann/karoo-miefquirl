{
  description = "karoo-miefquirl — Hammerhead Karoo 3 extension for controlling the Wahoo KICKR Headwind fan";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f system);

      pkgsFor = system: import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # Pinned so every developer dexes against the same platform/build-tools.
      buildToolsVersion = "35.0.0";
      platformVersion = "35";

      androidFor = system:
        (pkgsFor system).androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "13.0";
          platformToolsVersion = "37.0.1";
          buildToolsVersions = [ buildToolsVersion ];
          platformVersions = [ platformVersion "34" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeNDK = false;
          includeSources = false;
        };
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = pkgsFor system;
          android = androidFor system;
          sdk = "${android.androidsdk}/libexec/android-sdk";
          # AGP otherwise downloads its own aapt2, which is not patched for Nix.
          aapt2 = "${sdk}/build-tools/${buildToolsVersion}/aapt2";
        in
        {
          default = pkgs.mkShell {
            name = "miefquirl";

            packages = [
              pkgs.jdk17            # AGP 8.9 requires JDK 17
              pkgs.gradle
              android.androidsdk
              # Release signing material lives encrypted in secrets.yaml; CI
              # decrypts it with a dedicated age key.
              pkgs.sops
              pkgs.age
              pkgs.kotlin-language-server
            ];

            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;
            JAVA_HOME = "${pkgs.jdk17}";

            GRADLE_OPTS =
              "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2} "
              + "-Dorg.gradle.project.android.sdk.channel=3";

            shellHook = ''
              echo "miefquirl dev shell"
              echo "  jdk       $(java -version 2>&1 | head -1)"
              echo "  android   $ANDROID_HOME"
              echo
              echo "  make test     run the domain-logic tests (no SDK needed)"
              echo "  make apk      assemble the debug APK"
              echo "  make install  sideload onto a connected Karoo"
            '';
          };
        });

      formatter = forAllSystems (system: (pkgsFor system).nixpkgs-fmt);
    };
}
