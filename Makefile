.PHONY: build build-backend build-frontend test test-backend test-frontend test-frontend-critical runtime-guard secret-scan

FRONTEND_CRITICAL_VITEST := \
	src/views/auth/__tests__/CommunityCallbackView.spec.ts \
	src/views/auth/__tests__/WechatCallbackView.spec.ts \
	src/views/user/__tests__/PaymentView.spec.ts \
	src/views/user/__tests__/PaymentResultView.spec.ts \
	src/components/user/profile/__tests__/ProfileInfoCard.spec.ts \
	src/views/admin/__tests__/SettingsView.spec.ts

JAVA_MVN := JAVA_TOOL_OPTIONS=-Dmaven.multiModuleProjectDirectory=$(CURDIR)/java-backend java -cp .mvn/wrapper/maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain

build: runtime-guard build-frontend build-backend

build-backend:
	@cd java-backend && $(JAVA_MVN) -q -DskipTests package

build-frontend:
	@pnpm --dir frontend run build

test: runtime-guard test-backend test-frontend

test-backend:
	@cd java-backend && $(JAVA_MVN) -q test

test-frontend:
	@pnpm --dir frontend run lint:check
	@pnpm --dir frontend run typecheck
	@$(MAKE) test-frontend-critical

test-frontend-critical:
	@pnpm --dir frontend exec vitest run $(FRONTEND_CRITICAL_VITEST)

runtime-guard:
	@python3 tools/check_java_default_runtime.py
