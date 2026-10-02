# Owner correction: English-only UI

The owner explicitly clarified that all app UI must be English. This overrides every earlier English/Dutch requirement in implementation.md and review plans. Dutch was the supervisor's assumption, not a user requirement.

Implementation by ZCode GLM-5.3-Flash:
1. Remove app/src/main/res/values-nl/strings.xml and any Dutch-only app resources or locale declarations. Retain values/ English strings and accessibility text. With no alternate app resources, a Dutch system locale must still render Morsel's UI in English.
2. Remove Dutch-specific instrumentation/setup and script phases; instead verify English app strings under a Dutch system/app locale to prove English-only behavior. Keep existing unrelated screenshot/test assertions.
3. Update README, docs/testing.md, docs/implementation-status.md and DEVLOG to state English-only. Historical evidence may say Dutch was tested previously, but must not present Dutch as a current feature or remaining gate.
4. Run existing formatter/lint/unit/build and full emulator CI on final code, capture English screenshots, commit/push. No feature expansion, no new locale selector or framework.
5. Read plans/review-success-attribution.md too before release; unrelated safety reviews remain in effect.
