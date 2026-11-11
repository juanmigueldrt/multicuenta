use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if let Ok(out_dir) = env::var("OUT_DIR") {
        let out_path = PathBuf::from(out_dir);
        // target/debug/build/<pkg>/out -> parent x3 is target/debug
        if let Some(target_profile_dir) = out_path.ancestors().nth(3) {
            let mingw_bin = PathBuf::from(
                "C:/Users/Agora/.llvm-mingw/llvm-mingw-20261104-ucrt-x86_64/bin",
            );
            for dll in ["libunwind.dll", "libc++.dll", "libwinpthread-1.dll"] {
                let src = mingw_bin.join(dll);
                if src.exists() {
                    let dst = target_profile_dir.join(dll);
                    let _ = fs::copy(&src, &dst);
                }
            }
            // gnu/gnullvm targets link WebView2 dynamically: the exe needs the loader beside it
            let loader = PathBuf::from("vendor/webview2/x64/WebView2Loader.dll");
            println!("cargo:rerun-if-changed=vendor/webview2/x64/WebView2Loader.dll");
            if loader.exists() {
                let _ = fs::copy(&loader, target_profile_dir.join("WebView2Loader.dll"));
            }
        }
    }
}
