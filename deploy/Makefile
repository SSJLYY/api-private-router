.PHONY: build test-unit

JAVA_MVN := JAVA_TOOL_OPTIONS=-Dmaven.multiModuleProjectDirectory=$(CURDIR)/../java-backend java -cp .mvn/wrapper/maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain

build:
	@echo "Building Java backend release artifact..."
	@cd ../frontend && pnpm run build
	@cd ../java-backend && $(JAVA_MVN) -q -DskipTests package
	@echo "Build completed: ../java-backend/target/api-private-router.jar"

test-unit:
	@echo "Running Java backend tests..."
	@cd ../java-backend && $(JAVA_MVN) -q test
