package com.example.unifiedqarunner;

import java.util.List;

/**
 * Generates CI/CD pipeline files for GitHub Actions and Jenkins.
 * Templates are embedded in the code — no external files needed.
 */
public class WorkflowGenerator {

    /**
     * Generate GitHub Actions YAML workflow.
     */
    public String generateGitHubActions(CiCdConfig.CiCdSettings settings) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: QA Runner\n\n");
        yaml.append("on:\n");

        String triggerType = settings.triggerType();
        if ("push".equals(triggerType) || "all".equals(triggerType)) {
            yaml.append("  push:\n");
            yaml.append("    branches: [ '" + settings.branch() + "' ]\n");
        }
        if ("pr".equals(triggerType) || "all".equals(triggerType)) {
            yaml.append("  pull_request:\n");
            yaml.append("    branches: [ '" + settings.branch() + "' ]\n");
        }
        if ("schedule".equals(triggerType) || "all".equals(triggerType)) {
            yaml.append("  schedule:\n");
            String cron = settings.scheduleCron().isEmpty() ? "0 0 * * *" : settings.scheduleCron();
            yaml.append("    - cron: '" + cron + "'\n");
        }
        yaml.append("  workflow_dispatch:\n\n");

        yaml.append("jobs:\n");
        yaml.append("  test:\n");
        yaml.append("    runs-on: ubuntu-latest\n\n");
        yaml.append("    steps:\n");
        yaml.append("      - name: Checkout code\n");
        yaml.append("        uses: actions/checkout@v4\n\n");

        // Setup Java
        yaml.append("      - name: Set up JDK 17\n");
        yaml.append("        uses: actions/setup-java@v4\n");
        yaml.append("        with:\n");
        yaml.append("          java-version: '17'\n");
        yaml.append("          distribution: 'temurin'\n");
        yaml.append("          cache: maven\n\n");

        // Setup Node.js if needed
        boolean needsNode = hasFramework(settings.frameworks(), "newman", "webdio", "cypress");
        if (needsNode) {
            yaml.append("      - name: Set up Node.js\n");
            yaml.append("        uses: actions/setup-node@v4\n");
            yaml.append("        with:\n");
            yaml.append("          node-version: '18'\n\n");
        }

        // Setup Python if needed
        boolean needsPython = hasFramework(settings.frameworks(), "pytest", "robot");
        if (needsPython) {
            yaml.append("      - name: Set up Python\n");
            yaml.append("        uses: actions/setup-python@v5\n");
            yaml.append("        with:\n");
            yaml.append("          python-version: '3.11'\n\n");
        }

        // Install dependencies
        if (needsNode) {
            yaml.append("      - name: Install Node.js dependencies\n");
            yaml.append("        run: npm install\n\n");
        }
        if (needsPython) {
            yaml.append("      - name: Install Python dependencies\n");
            yaml.append("        run: |\n");
            yaml.append("          pip install --upgrade pip\n");
            yaml.append("          pip install pytest selenium webdriver-manager\n");
            if (hasFramework(settings.frameworks(), "robot")) {
                yaml.append("          pip install robotframework robotframework-seleniumlibrary\n");
            }
            yaml.append("\n");
        }

        // Run tests per framework
        List<String> frameworks = settings.frameworks();
        if (frameworks.isEmpty()) {
            // Default: run all Maven tests
            yaml.append("      - name: Run all tests\n");
            yaml.append("        run: mvn -DskipTests package && java -jar target/unified-qa-runner-1.0-SNAPSHOT.jar &\n");
            yaml.append("        timeout-minutes: 30\n\n");
        } else {
            // Maven-based frameworks
            boolean hasMaven = hasFramework(frameworks, "cucumber", "playwright", "karate", "restassured", "testng", "gatling");
            if (hasMaven) {
                yaml.append("      - name: Run Maven-based tests\n");
                yaml.append("        run: mvn -DskipTests package\n\n");
            }

            // Newman
            if (hasFramework(frameworks, "newman")) {
                yaml.append("      - name: Run Newman tests\n");
                yaml.append("        run: npx newman run src/test/resources/api/local-health.postman_collection.json\n\n");
            }

            // WebdriverIO
            if (hasFramework(frameworks, "webdio")) {
                yaml.append("      - name: Run WebdriverIO tests\n");
                yaml.append("        run: npx wdio tests/wdio/wdio.conf.js\n\n");
            }

            // Cypress
            if (hasFramework(frameworks, "cypress")) {
                yaml.append("      - name: Run Cypress tests\n");
                yaml.append("        run: npx cypress run\n\n");
            }

            // Pytest
            if (hasFramework(frameworks, "pytest")) {
                yaml.append("      - name: Run Pytest tests\n");
                yaml.append("        run: python -m pytest tests/pytest/\n\n");
            }

            // Robot Framework
            if (hasFramework(frameworks, "robot")) {
                yaml.append("      - name: Run Robot Framework tests\n");
                yaml.append("        run: python -m robot tests/robot/\n\n");
            }
        }

        // Upload reports
        yaml.append("      - name: Upload test reports\n");
        yaml.append("        if: always()\n");
        yaml.append("        uses: actions/upload-artifact@v4\n");
        yaml.append("        with:\n");
        yaml.append("          name: test-reports\n");
        yaml.append("          path: |\n");
        yaml.append("            target/runner-reports/\n");
        yaml.append("            target/surefire-reports/\n");
        yaml.append("          retention-days: 7\n");

        return yaml.toString();
    }

    /**
     * Generate Jenkinsfile (Groovy).
     */
    public String generateJenkinsfile(CiCdConfig.CiCdSettings settings) {
        StringBuilder groovy = new StringBuilder();
        groovy.append("pipeline {\n");
        groovy.append("    agent any\n\n");

        // Environment variables
        groovy.append("    environment {\n");
        groovy.append("        JAVA_HOME = tool 'JDK-17'\n");
        groovy.append("        NODE_HOME = tool 'NodeJS-18'\n");
        groovy.append("    }\n\n");

        // Triggers
        groovy.append("    triggers {\n");
        String triggerType = settings.triggerType();
        if ("schedule".equals(triggerType) || "all".equals(triggerType)) {
            String cron = settings.scheduleCron().isEmpty() ? "H 0 * * *" : settings.scheduleCron();
            groovy.append("        cron('" + cron + "')\n");
        }
        groovy.append("    }\n\n");

        // Stages
        groovy.append("    stages {\n");

        // Checkout
        groovy.append("        stage('Checkout') {\n");
        groovy.append("            steps {\n");
        groovy.append("                checkout scm\n");
        groovy.append("            }\n");
        groovy.append("        }\n\n");

        // Setup Java
        groovy.append("        stage('Setup Java') {\n");
        groovy.append("            steps {\n");
        groovy.append("                sh 'java -version'\n");
        groovy.append("            }\n");
        groovy.append("        }\n\n");

        // Setup Node.js if needed
        boolean needsNode = hasFramework(settings.frameworks(), "newman", "webdio", "cypress");
        if (needsNode) {
            groovy.append("        stage('Setup Node.js') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'node --version'\n");
            groovy.append("                sh 'npm install'\n");
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }

        // Setup Python if needed
        boolean needsPython = hasFramework(settings.frameworks(), "pytest", "robot");
        if (needsPython) {
            groovy.append("        stage('Setup Python') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'python --version'\n");
            groovy.append("                sh 'pip install pytest selenium webdriver-manager'\n");
            if (hasFramework(settings.frameworks(), "robot")) {
                groovy.append("                sh 'pip install robotframework robotframework-seleniumlibrary'\n");
            }
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }

        // Build
        groovy.append("        stage('Build') {\n");
        groovy.append("            steps {\n");
        groovy.append("                sh 'mvn -DskipTests package'\n");
        groovy.append("            }\n");
        groovy.append("        }\n\n");

        // Run tests per framework
        List<String> frameworks = settings.frameworks();
        if (hasFramework(frameworks, "cucumber", "playwright", "karate", "restassured", "testng", "gatling")) {
            groovy.append("        stage('Run Maven Tests') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'mvn test'\n");
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }
        if (hasFramework(frameworks, "newman")) {
            groovy.append("        stage('Run Newman Tests') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'npx newman run src/test/resources/api/local-health.postman_collection.json'\n");
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }
        if (hasFramework(frameworks, "webdio")) {
            groovy.append("        stage('Run WebdriverIO Tests') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'npx wdio tests/wdio/wdio.conf.js'\n");
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }
        if (hasFramework(frameworks, "cypress")) {
            groovy.append("        stage('Run Cypress Tests') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'npx cypress run'\n");
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }
        if (hasFramework(frameworks, "pytest")) {
            groovy.append("        stage('Run Pytest Tests') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'python -m pytest tests/pytest/'\n");
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }
        if (hasFramework(frameworks, "robot")) {
            groovy.append("        stage('Run Robot Framework Tests') {\n");
            groovy.append("            steps {\n");
            groovy.append("                sh 'python -m robot tests/robot/'\n");
            groovy.append("            }\n");
            groovy.append("        }\n\n");
        }

        // Archive reports
        groovy.append("        stage('Archive Reports') {\n");
        groovy.append("            steps {\n");
        groovy.append("                archiveArtifacts artifacts: 'target/runner-reports/**', allowEmptyArchive: true\n");
        groovy.append("                junit 'target/surefire-reports/TEST-*.xml'\n");
        groovy.append("            }\n");
        groovy.append("        }\n\n");

        // Post actions
        groovy.append("    }\n\n");
        groovy.append("    post {\n");
        groovy.append("        always {\n");
        groovy.append("            cleanWs()\n");
        groovy.append("        }\n");
        groovy.append("        failure {\n");
        groovy.append("            mail to: 'team@example.com',\n");
        groovy.append("                 subject: \"QA Runner Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}\",\n");
        groovy.append("                 body: \"Check console output at ${env.BUILD_URL}\"\n");
        groovy.append("        }\n");
        groovy.append("    }\n");
        groovy.append("}\n");

        return groovy.toString();
    }

    private boolean hasFramework(List<String> frameworks, String... names) {
        for (String fw : frameworks) {
            for (String name : names) {
                if (fw.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
