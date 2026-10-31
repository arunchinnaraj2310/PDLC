package com.pdlc.assistant.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the design in step with the code. Every few seconds it fingerprints the source folder of each project that
 * has been synced; when the fingerprint changes and stays the same for one more poll (the editor has finished
 * saving), it re-runs the code sync and applies the result, so tree-sitter's view of the code redraws the
 * diagram without anyone asking. Only the files whose hash changed are parsed again, and the LLM is only called
 * for components it has not named yet. Code generation pauses the watcher while it writes files.
 */
@Service
public class CodeWatchService {

	private static final Logger log = LoggerFactory.getLogger(CodeWatchService.class);

	private final ProjectService projects;

	private final CodeSyncService sync;

	private final CodeInventoryService inventory;

	private final boolean enabled;

	private final Set<String> paused = ConcurrentHashMap.newKeySet();

	private final Set<String> running = ConcurrentHashMap.newKeySet();

	/** Fingerprint seen on the previous poll when it differed from the synced one. */
	private final Map<String, String> pending = new ConcurrentHashMap<>();

	public CodeWatchService(ProjectService projects, CodeSyncService sync, CodeInventoryService inventory,
			@Value("${pdlc.code.auto-sync:true}") boolean enabled) {
		this.projects = projects;
		this.sync = sync;
		this.inventory = inventory;
		this.enabled = enabled;
	}

	public void pause(String projectId) {
		paused.add(projectId);
	}

	public void resume(String projectId) {
		paused.remove(projectId);
		pending.remove(projectId);
	}

	@Scheduled(fixedDelayString = "${pdlc.code.poll-ms:10000}", initialDelayString = "${pdlc.code.poll-ms:10000}")
	void poll() {
		if (!enabled) {
			return;
		}
		for (String id : projects.listProjects()) {
			if (paused.contains(id) || running.contains(id)) {
				continue;
			}
			try {
				check(id);
			}
			catch (RuntimeException e) {
				log.warn("Code watch of {} failed: {}", id, e.getMessage());
			}
		}
	}

	private void check(String id) {
		String source = sync.knownSource(id);
		if (source == null || !Files.isDirectory(Path.of(source))) {
			return;
		}
		String now = inventory.signature(Path.of(source));
		CodeSyncService.AutoSync last = sync.autoStatus(id);
		if (last.signature() == null || !source.equals(last.source())) {
			// first sight of this source: nothing is known to have changed, so just remember where it stands
			sync.recordBaseline(id, source, now);
			return;
		}
		if (now.equals(last.signature())) {
			pending.remove(id);
			return;
		}
		if (!now.equals(pending.put(id, now))) {
			return;
		}
		pending.remove(id);
		running.add(id);
		try {
			String report = sync.syncAndApply(id, source, status -> {
			});
			log.info("code watch project={} resynced: {}", id, report.lines().findFirst().orElse(""));
		}
		catch (RuntimeException e) {
			log.warn("Automatic code sync of {} failed: {}", id, e.getMessage());
			sync.recordError(id, e.getMessage());
			// do not retry the same state every poll: remember it as seen
			sync.recordBaseline(id, source, now);
		}
		finally {
			running.remove(id);
		}
	}

}
