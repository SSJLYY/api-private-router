.PHONY: build test-unit

JAVA_BACKEND_DIR := $(CURDIR)/../java-backend
JAVA_MVN := JAVA_TOOL_OPTIONS=-Dmaven.multiModuleProjectDirectory=$(JAVA_BACKEND_DIR) java -cp $(JAVA_BACKEND_DIR)/.mvn/wrapper/maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain

build:
	@echo "Building Java backend release artifact..."
	@cd ../frontend && pnpm run build
	@cd $(JAVA_BACKEND_DIR) && $(JAVA_MVN) -q -DskipTests package
	@echo "Build completed: $(JAVA_BACKEND_DIR)/target/api-private-router.jar"

test-unit:
	@echo "Running Java backend tests..."
	@cd $(JAVA_BACKEND_DIR) && $(JAVA_MVN) -q test
