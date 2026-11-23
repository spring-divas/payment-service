import com.google.protobuf.gradle.id

plugins {
	java
	checkstyle
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.6"
}

group = "org.spring.divas"
version = "0.0.1-SNAPSHOT"
description = "payment-service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

val checkstyleVersion = "13.7.0"
val protobufVersion = "4.36.2"
val grpcVersion = "1.84.1"
val mapstructVersion = "1.6.3"
val logstashLogbackEncoderVersion = "9.0"
val lombokMapstructBindingVersion = "0.2.0"
val pactVersion = "4.7.5"
val chaosVersion = "4.0.0"
val resilience4jVersion = "2.4.0"

checkstyle {
	toolVersion = checkstyleVersion
	configFile = file("checks.xml")
}

repositories {
	mavenCentral()
}

dependencies {
	// Production dependencies - Spring Boot Starters & Framework
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-aspectj")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("de.codecentric:chaos-monkey-spring-boot:$chaosVersion")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("io.github.resilience4j:resilience4j-spring-boot4:$resilience4jVersion")

	// Production dependencies - gRPC Server & Protobuf
	implementation("org.springframework.boot:spring-boot-starter-grpc-server")
	implementation("io.grpc:grpc-services")
	implementation("io.grpc:grpc-protobuf")
	implementation("io.grpc:grpc-stub")
	implementation("com.google.protobuf:protobuf-java:$protobufVersion")

	// Production dependencies - Logging & Utilities
	implementation("net.logstash.logback:logstash-logback-encoder:$logstashLogbackEncoderVersion")
	implementation("org.mapstruct:mapstruct:$mapstructVersion")

	// Compile-only dependencies
	compileOnly("jakarta.annotation:jakarta.annotation-api")
	compileOnly("org.projectlombok:lombok")

	// Annotation processors
	annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")
	annotationProcessor("org.projectlombok:lombok-mapstruct-binding:$lombokMapstructBindingVersion")
	annotationProcessor("org.projectlombok:lombok")

	// Runtime dependencies
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.springframework.boot:spring-boot-restclient")

	// Test dependencies
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-grpc-server-test")
	testImplementation("com.h2database:h2")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("au.com.dius.pact.consumer:junit5:$pactVersion")

	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc:$protobufVersion"
	}
	plugins {
		id("grpc") {
			artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
		}
	}
	generateProtoTasks {
		all().forEach { task ->
			task.plugins {
				// By default, plugins registered above are applied, or we can configure them
			}
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty(
		"pact.rootDir",
		System.getProperty("pact.rootDir", project.file("../common/contract/pact").absolutePath)
	)
}

tasks.named<Checkstyle>("checkstyleMain"){
	source = fileTree("src/main/java")
}