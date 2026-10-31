package com.pdlc.assistant.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	private final String appName;

	public HealthController(@Value("${spring.application.name}") String appName) {
		this.appName = appName;
	}

	@GetMapping("/api/health")
	public Map<String, String> health() {
		Map<String, String> info = new LinkedHashMap<>();
		info.put("status", "ok");
		info.put("app", appName);
		info.put("javaVersion", System.getProperty("java.version"));
		info.put("springBootVersion", SpringBootVersion.getVersion());
		info.put("time", Instant.now().toString());
		return info;
	}

}
