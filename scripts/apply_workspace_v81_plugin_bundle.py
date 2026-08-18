#!/usr/bin/env python3
from pathlib import Path
import runpy

HERE = Path(__file__).resolve().parent
runpy.run_path(str(HERE / 'apply_workspace_v81_plugin_bundle_impl.py'), run_name='__main__')
runpy.run_path(str(HERE / 'apply_workspace_v81_fresh_install.py'), run_name='__main__')
