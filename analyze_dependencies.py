#!/usr/bin/env python3

import os
import re
import subprocess
from pathlib import Path

def find_imports_in_source():
    """Find all imports in the source code"""
    imports = set()
    source_dir = Path("telnyx_rtc/src/main/java")
    
    for file_path in source_dir.rglob("*.kt"):
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
            # Find import statements
            import_matches = re.findall(r'^import\s+([^\s]+)', content, re.MULTILINE)
            imports.update(import_matches)
    
    return imports

def check_dependency_usage():
    """Check which dependencies are actually used"""
    imports = find_imports_in_source()
    
    # Define dependency patterns to check
    dependency_patterns = {
        'retrofit': ['retrofit2'],
        'ktor': ['io.ktor'],
        'dexter': ['com.karumi.dexter'],
        'firebase': ['com.google.firebase'],
        'hilt': ['dagger.hilt', 'javax.inject'],
        'gson': ['com.google.gson'],
        'coroutines': ['kotlinx.coroutines'],
        'timber': ['timber.log'],
        'okhttp': ['okhttp3'],
        'androidx_core': ['androidx.core'],
        'appcompat': ['androidx.appcompat'],
        'material': ['com.google.android.material'],
        'constraint_layout': ['androidx.constraintlayout'],
        'lifecycle': ['androidx.lifecycle'],
        'websocket': ['org.java_websocket', 'java_websocket'],
        'webrtc_lib': ['com.telnyx.webrtc.lib']
    }
    
    used_deps = set()
    unused_deps = set()
    
    for dep_name, patterns in dependency_patterns.items():
        is_used = any(any(pattern in imp for pattern in patterns) for imp in imports)
        if is_used:
            used_deps.add(dep_name)
        else:
            unused_deps.add(dep_name)
    
    return used_deps, unused_deps, imports

def main():
    os.chdir('/workspace/telnyx-webrtc-android')
    
    used_deps, unused_deps, all_imports = check_dependency_usage()
    
    print("=== DEPENDENCY ANALYSIS ===")
    print(f"\nUSED DEPENDENCIES ({len(used_deps)}):")
    for dep in sorted(used_deps):
        print(f"  ✓ {dep}")
    
    print(f"\nPOTENTIALLY UNUSED DEPENDENCIES ({len(unused_deps)}):")
    for dep in sorted(unused_deps):
        print(f"  ✗ {dep}")
    
    print(f"\nALL IMPORTS FOUND ({len(all_imports)}):")
    for imp in sorted(all_imports):
        print(f"  - {imp}")

if __name__ == "__main__":
    main()