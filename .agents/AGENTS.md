# Documentation Maintenance Rule

- **ALWAYS Update Documentation on Code Changes**: Whenever you add a new feature, significantly refactor existing logic, or change the data models (`TradingModels.kt` or `schemas.py`) in this project, you MUST locate and update `ARCHITECTURE.md` and the relevant `README.md` files to reflect your changes accurately.
- **Explain the "Why"**: In the documentation updates, do not just list what changed; briefly explain *how* it affects the data flow (Firebase) or the core logic (Android App or Python Engine).
- **Keep Mermaid Charts Updated**: If architectural flows or module relationships change, ensure the Mermaid diagrams in `ARCHITECTURE.md` are updated to match the latest system design.
- **Check Before Asking**: Before modifying core data structures, always consult `ARCHITECTURE.md` first to understand the existing contract between the Android App and the Python Engine.

- **Update USERGUIDE.md on Feature Changes**: Whenever you add, modify, or remove features related to the user interface, manual trading, surge trading, or API key configuration, you MUST update USERGUIDE.md to reflect the new user-facing instructions and usage methods.

## Python Backend Deployment Rule
Whenever modifying any .py code in the py-my-auto-trading Python backend project, you MUST always execute D:\01.SW\03.Tool\01.AndroidStudio\AndroidStudioProjects\MyAutoTrading\py-my-auto-trading\deploy.bat after making the changes to ensure the Oracle server backend is updated.

## Code Verification Rule
- **Android App Changes**: Whenever you modify the Android App code (.kt, .xml, etc.), you MUST run ./gradlew assembleDebug in the app directory to verify the build completes successfully before reporting success to the user.
- **Python Backend Changes**: Whenever you modify the Python backend code (.py), you MUST run deploy.bat to verify the deployment completes successfully before reporting success to the user.
