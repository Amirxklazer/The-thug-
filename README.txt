14AI v2.4.1  (FLAT zip: no folders)
1. On GitHub create a repo. Upload ALL files from this zip to the repo root (no folders needed).
2. Create the workflow: Add file -> Create new file, name it  .github/workflows/build.yml  and paste the contents of build.yml (the upload of build.yml alone won't run).
3. Actions tab -> Build APK -> run -> download app-debug-apk.
build.yml sorts files named like app@@src@@main@@... back into folders before building.
4. Open the app, sign in (email or guest), paste your Puter token (puter.com/dashboard) or a Gemini/Qwen key.
