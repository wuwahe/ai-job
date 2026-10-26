# Repository Guidelines

## Project Structure & Module Organization

- `ai-job-hunting-ui/`: Vue 3, TypeScript, Pinia, and Element Plus userscript built with Vite and `vite-plugin-monkey`. `src/components/` contains UI, `stores/` manages state, `platform/` implements recruitment workflows, and `webSocket/` handles interception and Protobuf messages.
- `ai-job-hunting-server/`: Java 17 / Spring Boot 3.2.5 backend. Application code lives under `src/main/java/com/maple/ai/job/hunting/`; resources contain SQL, configuration, and email templates. Tests live in `src/test/java/`.
- `file/`: documentation images. Root `*.user.js` files are distributed scripts; change source files first and update bundles only for intentional releases.

## Build, Test, and Development Commands

Run frontend commands inside `ai-job-hunting-ui/` (Node.js 18+ and pnpm):

- `pnpm install --frozen-lockfile`: install locked dependencies.
- `pnpm dev`: start Vite; install the development userscript through Tampermonkey.
- `pnpm build`: run TypeScript checks and generate `dist/ai-job-hunting.user.js`.
- `pnpm preview`: preview the production build locally.

Run backend commands inside `ai-job-hunting-server/`:

- `mvn test`: execute backend tests.
- `mvn package`: test and build `target/ai-job.jar`.
- `mvn spring-boot:run`: start the backend with local configuration.

The POM hardcodes a Windows `javac.exe` path. Override it for your JDK, for example with `-Dmaven.compiler.executable="$JAVA_HOME/bin/javac"`. Configure local infrastructure before starting Spring integration tests.

## Coding Style & Naming Conventions

Match surrounding formatting; Java and much of the TypeScript use four-space indentation. Use PascalCase for Java classes and Vue components, camelCase for methods and variables, and existing package conventions. Keep TypeScript strict mode enabled. Keep platform behavior in `platform/` and backend business rules in services. No dedicated ESLint or Prettier configuration is present.

## Testing Guidelines

Backend tests use JUnit through Spring Boot Test; name tests `*Test.java`. Cover business rules, invalid inputs, and failure paths. No coverage threshold or frontend test command is configured. Run `pnpm build` and manually verify userscript mounting, settings, and affected workflows. Isolate AI and email dependencies; avoid real messages or paid requests during verification.

## Commit & Pull Request Guidelines

History mixes free-form messages with `docs(readme): ...`. Use focused Conventional Commits such as `fix(ui): handle disconnected server`. Write concise English subjects. PRs should explain behavior changes, link relevant issues, report verification, and include screenshots for UI changes. Flag configuration and protocol compatibility impacts.

## Security & Configuration

Never commit credentials, tokens, resumes, or private chat data. Use local configuration and placeholders in examples. Keep sensitive information out of logs and screenshots.
