# Documentation Maintenance Rule

- **ALWAYS Update Documentation on Code Changes**: Whenever you add a new feature, significantly refactor existing logic, or change the data models (`TradingModels.kt` or `schemas.py`) in this project, you MUST locate and update `ARCHITECTURE.md` and the relevant `README.md` files to reflect your changes accurately.
- **Explain the "Why"**: In the documentation updates, do not just list what changed; briefly explain *how* it affects the data flow (Firebase) or the core logic (Android App or Python Engine).
- **Keep Mermaid Charts Updated**: If architectural flows or module relationships change, ensure the Mermaid diagrams in `ARCHITECTURE.md` are updated to match the latest system design.
- **Check Before Asking**: Before modifying core data structures, always consult `ARCHITECTURE.md` first to understand the existing contract between the Android App and the Python Engine.
