# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Optional Nix flake devShells for DuoIME.
# GitHub Actions (.github/workflows/build-rime-native.yml) remains the canonical
# reproducible builder for 16 KB Android native Rime binaries; this flake offers
# a standard Kotlin/hygiene shell and an optional Linux Android NDK shell.
{
  description = "DuoIME development shells (default JVM/hygiene + optional Android NDK)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
        "x86_64-darwin"
      ];
      forAllSystems = f:
        builtins.listToAttrs (map (system: {
          name = system;
          value = f system;
        }) systems);
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          basePackages = with pkgs; [
            jdk21
            cmake
            ninja
            python3
            shellcheck
            binutils
            git
            curl
          ];
          isLinuxX64 = system == "x86_64-linux";
        in
        {
          default = pkgs.mkShell {
            name = "duoime-dev";
            packages = basePackages;
            shellHook = ''
              export JAVA_HOME="${pkgs.jdk21}"
            '';
          };
        }
        // (if isLinuxX64 then
          let
            androidPkgs = import nixpkgs {
              inherit system;
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
            };
            androidComposition = androidPkgs.androidenv.composeAndroidPackages {
              cmdLineToolsVersion = "13.0";
              platformToolsVersion = "35.0.2";
              buildToolsVersions = [ "34.0.0" ];
              platformVersions = [ "35" ];
              includeNDK = true;
              ndkVersions = [ "27.2.12479018" ];
              cmakeVersions = [ "3.22.1" ];
            };
            sdkRoot = "${androidComposition.androidsdk}/libexec/android-sdk";
          in
          {
            android-ndk = androidPkgs.mkShell {
              name = "duoime-android-ndk";
              packages = basePackages ++ [ androidComposition.androidsdk ];
              shellHook = ''
                export JAVA_HOME="${pkgs.jdk21}"
                export ANDROID_HOME="${sdkRoot}"
                export ANDROID_SDK_ROOT="${sdkRoot}"
                export ANDROID_NDK_HOME="${sdkRoot}/ndk/27.2.12479018"
              '';
            };
          }
        else
          { })
      );
    };
}
