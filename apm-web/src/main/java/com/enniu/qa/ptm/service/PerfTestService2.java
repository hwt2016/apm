/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package com.enniu.qa.ptm.service;

import com.enniu.qa.ptm.configuration.Config;
import com.enniu.qa.ptm.configuration.constant.ControllerConstants;
import com.enniu.qa.ptm.dao.ApiTestRunDao;
import com.enniu.qa.ptm.handler.NullScriptHandler;
import com.enniu.qa.ptm.handler.ProcessingResultPrintStream;
import com.enniu.qa.ptm.handler.ScriptHandler;
import com.enniu.qa.ptm.handler.ScriptHandlerFactory;
import com.enniu.qa.ptm.model.*;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.grinder.SingleConsole;
import net.grinder.StopReason;
import net.grinder.common.GrinderProperties;
import net.grinder.console.communication.AgentProcessControlImplementation.AgentStatus;
import net.grinder.console.model.ConsoleProperties;
import net.grinder.util.ConsolePropertiesFactory;
import net.grinder.util.Directory;
import net.grinder.util.Pair;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;

import org.ngrinder.common.constants.GrinderConstants;

import org.ngrinder.model.*;
import org.ngrinder.model.RampUp;
import org.ngrinder.model.Role;
import org.ngrinder.monitor.controller.model.SystemDataModel;
import org.python.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import static org.ngrinder.common.constants.MonitorConstants.MONITOR_FILE_PREFIX;
import static org.ngrinder.common.util.AccessUtils.getSafe;
import static org.ngrinder.common.util.CollectionUtils.*;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.NoOp.noOp;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;
import static org.ngrinder.model.Status.getProcessingOrTestingTestStatus;
import static com.enniu.qa.ptm.dao.PerfTestSpecification2.*;

/**
 * {@link PerfTest} Service Class.
 * <p/>
 * This class contains various method which mainly get the {@link PerfTest} matching specific conditions from DB.
 *
 * @author JunHo Yoon
 * @author Mavlarn
 * @since 3.0
 */

@Service
public class PerfTestService2 implements ControllerConstants, GrinderConstants {

	private static final int MAX_POINT_COUNT = 100;

	private static final Logger LOGGER = LoggerFactory.getLogger(PerfTestService2.class);

	private static final String DATA_FILE_EXTENSION = ".data";

	@Autowired
	private ApiTestRunDao apiTestRunDao;

	@Autowired
	ApiTestConfigService configService;

	@Autowired
	TestReportService reportService;

	@Autowired
	private ConsoleManager consoleManager;

	@Autowired
	private AgentManager agentManager;

	@Autowired
	private Config config;

	@Autowired
	private FileEntryService fileEntryService;

	@Autowired
	private ScriptHandlerFactory scriptHandlerFactory;

	/**
	 * Get {@link PerfTest} list for the given user.
	 *
	 * @param user        user
	 * @param query       query string on test name or description
	 * @param tag         search tag.
	 * @param queryFilter "S" for querying scheduled test, "F" for querying finished test
	 * @param pageable    paging info
	 * @return found {@link PerfTest} list
	 */
	public Page<ApiTestRun> getPagedAll(User user, String query, String tag, String queryFilter, Pageable pageable) {
		Specifications<ApiTestRun> spec = Specifications.where(idEmptyPredicate());
		// User can see only his own test
		if (user.getRole().equals(Role.USER)) {
			spec = spec.and(createdBy(user));
		}

		if (StringUtils.isNotBlank(tag)) {
			spec = spec.and(hasTag(tag));
		}
		if ("F".equals(queryFilter)) {
			spec = spec.and(statusSetEqual(Status.FINISHED));
		} else if ("R".equals(queryFilter)) {
			spec = spec.and(statusSetEqual(Status.TESTING));
		} else if ("S".equals(queryFilter)) {
			spec = spec.and(statusSetEqual(Status.READY));
			spec = spec.and(scheduledTimeNotEmptyPredicate());
		}
		if (StringUtils.isNotBlank(query)) {
			spec = spec.and(likeTestNameOrDescription(query));
		}
		return apiTestRunDao.findAll(spec, pageable);
	}

	/**
	 * Get {@link ApiTestRun} list on the user.
	 *
	 * @param user user
	 * @return found {@link ApiTestRun} list
	 */
	List<ApiTestRun> getAll(User user) {
		Specifications<ApiTestRun> spec = Specifications.where(createdBy(user));
		return apiTestRunDao.findAll(spec);
	}


	public ApiTestRun getOne(Long testId) {
		return apiTestRunDao.findOne(testId);
	}
	
	public ApiTestRun getOne(User user, Long id) {
		Specifications<ApiTestRun> spec = Specifications.where(idEmptyPredicate());

		// User can see only his own test
		if (user.getRole().equals(Role.USER)) {
			spec = spec.and(createdBy(user));
		}
		spec = spec.and(idEqual(id));
		return apiTestRunDao.findOne(spec);
	}

	
	public List<ApiTestRun> getAll(User user, Long[] ids) {
		if (ids.length == 0) {
			return newArrayList();
		}
		Specifications<ApiTestRun> spec = Specifications.where(idEmptyPredicate());

		// User can see only his own test
		if (user.getRole().equals(Role.USER)) {
			spec = spec.and(createdBy(user));
		}
		spec = spec.and(idSetEqual(ids));
		return apiTestRunDao.findAll(spec);
	}

	
	public long count(User user, Status[] statuses) {
		Specifications<ApiTestRun> spec = Specifications.where(idEmptyPredicate());

		// User can see only his own test
		if (user != null) {
			spec = spec.and(createdBy(user));
		}

		if (statuses.length == 0) {
			return 0;
		} else {
			return apiTestRunDao.count(spec.and(statusSetEqual(statuses)));
		}

	}

	
	public List<ApiTestRun> getAll(User user, Status[] statuses) {
		Specifications<ApiTestRun> spec = Specifications.where(idEmptyPredicate());

		// User can see only his own test
		if (user != null) {
			spec = spec.and(createdBy(user));
		}
		if (statuses.length != 0) {
			spec = spec.and(statusSetEqual(statuses));
		}

		return apiTestRunDao.findAll(spec);
	}

	private List<ApiTestRun> getAll(User user, String region, Status[] statuses) {
		Specifications<ApiTestRun> spec = Specifications.where(idEmptyPredicate());
		// User can see only his own test
		if (user != null) {
			spec = spec.and(createdBy(user));
		}
		if (config.isClustered()) {
			spec = spec.and(idRegionEqual(region));
		}
		if (statuses.length != 0) {
			spec = spec.and(statusSetEqual(statuses));
		}

		return apiTestRunDao.findAll(spec);
	}


	/**
	 * Update runtime statistics on {@link PerfTest} having the given id.
	 *
	 * @param id            id of {@link PerfTest}
	 * @param runningSample runningSample json string
	 * @param agentState    agentState json string
	 */
	@Transactional
	public void updateRuntimeStatistics(Long id, String runningSample, String agentState) {
		apiTestRunDao.updateRuntimeStatistics(id, runningSample, agentState);
		apiTestRunDao.flush();
	}


	@Transactional
	public ApiTestRun markAbnormalTermination(ApiTestRun perfTest, StopReason reason) {
		return markAbnormalTermination(perfTest, reason.getDisplay());
	}

	@Transactional
	public ApiTestRun markAbnormalTermination(ApiTestRun perfTest, String reason) {
		// Leave last status as test error cause
		perfTest.setTestErrorCause(perfTest.getStatus());
		return markStatusAndProgress(perfTest, Status.ABNORMAL_TESTING, reason);
	}


	@Transactional
	public ApiTestRun markStatusAndProgress(ApiTestRun perfTest, Status status, String message) {
		perfTest.setStatus(checkNotNull(status, "status should not be null"));
		return markProgress(perfTest, message);
	}

	@Transactional
	public ApiTestRun markProgress(ApiTestRun perfTest, String message) {
		checkNotNull(perfTest);
		checkNotNull(perfTest.getId(), "perfTest should save Id");
		perfTest.setLastProgressMessage(message);
		LOGGER.debug("Progress : Test - {} : {}", perfTest.getId(), message);
		return apiTestRunDao.saveAndFlush(perfTest);
	}

	@Transactional
	public ApiTestRun markProgressAndStatus(ApiTestRun perfTest, Status status, String message) {
		perfTest.setStatus(status);
		return markProgress(perfTest, message);
	}

	@Transactional
	public ApiTestRun markProgressAndStatusAndFinishTimeAndStatistics(ApiTestRun perfTest, Status status, String message) {
		perfTest.setLastModifiedDate(new Date());
		updatePerfTestAfterTestFinish(perfTest);
		return markProgressAndStatus(perfTest, status, message);
	}

	@Transactional
	public ApiTestRun markPerfTestConsoleStart(ApiTestRun perfTest, int consolePort) {
		perfTest.setPort(consolePort);
		return markProgressAndStatus(perfTest, Status.START_CONSOLE_FINISHED, "Console is started on port "
				+ consolePort);
	}


	/**
	 * Get the next runnable {@link ApiTestRun}.
	 *
	 * @return found {@link ApiTestRun} which is ready to run, null otherwise
	 */
	@Transactional
	public ApiTestRun getNextRunnablePerfTestPerfTestCandidate() {
		List<ApiTestRun> readyPerfTests = apiTestRunDao.getAllByStatus(Status.READY);
		List<ApiTestRun> usersFirstPerfTests = filterCurrentlyRunningTestUsersTest(readyPerfTests);
		return usersFirstPerfTests.isEmpty() ? null : readyPerfTests.get(0);
	}

	/**
	 * Get currently running {@link ApiTestRun} list.
	 *
	 * @return running test list
	 */
	public List<ApiTestRun> getCurrentlyRunningTest() {
		return getAll(null, Status.getProcessingOrTestingTestStatus());
	}

	/**
	 * Filter out {@link ApiTestRun} whose owner is running another test now..
	 *
	 * @param perfTestLists perf test
	 * @return filtered perf test
	 */
	protected List<ApiTestRun> filterCurrentlyRunningTestUsersTest(List<ApiTestRun> perfTestLists) {
		List<ApiTestRun> currentlyRunningTests = getCurrentlyRunningTest();
		final Set<User> currentlyRunningTestOwners = newHashSet();
		for (ApiTestRun each : currentlyRunningTests) {
			currentlyRunningTestOwners.add(each.getCreatedUser());
		}
		CollectionUtils.filter(perfTestLists, new Predicate() {

			public boolean evaluate(Object object) {
				ApiTestRun perfTest = (ApiTestRun) object;
				return !currentlyRunningTestOwners.contains(perfTest.getCreatedUser());
			}
		});
		return perfTestLists;
	}

	
	public List<ApiTestRun> getAllTesting() {
		return getAll(null, config.getRegion(), Status.getTestingTestStates());
	}

	public List<ApiTestRun> getAllAbnormalTesting() {
		return getAll(null, config.getRegion(), new Status[]{Status.ABNORMAL_TESTING});
	}


	private void deletePerfTestDirectory(ApiTestRun perfTest) {
		FileUtils.deleteQuietly(getPerfTestDirectory(perfTest));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ngrinder.service.IPerfTestService#getPerfTestFilePath(org .ngrinder.perftest. model.PerfTest)
	 */
	
	public File getStatisticPath(ApiTestRun perfTest) {
		return config.getHome().getPerfTestStatisticPath(perfTest);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ngrinder.service.IPerfTestService#getPerfTestFilePath(org .ngrinder.perftest. model.PerfTest)
	 */
	
	public File getDistributionPath(ApiTestRun perfTest) {
		return config.getHome().getPerfTestDistDirectory(perfTest);
	}


	@SuppressWarnings("ResultOfMethodCallIgnored")
	public String getCustomClassPath(ApiTestRun perfTest) {
		File perfTestDirectory = getDistributionPath(perfTest);
		File libFolder = new File(perfTestDirectory, "lib");

		final StringBuffer customClassPath = new StringBuffer();
		customClassPath.append(".");
		if (libFolder.exists()) {
			customClassPath.append(File.pathSeparator).append("lib");
			libFolder.list(new FilenameFilter() {
				
				public boolean accept(File dir, String name) {
					if (name.endsWith(".jar")) {
						customClassPath.append(File.pathSeparator).append("lib/").append(name);
					}
					return true;
				}
			});
		}
		return customClassPath.toString();
	}

	/**
	 * Create {@link GrinderProperties} based on the passed {@link PerfTest}.
	 *
	 * @param perfTest base data
	 * @return created {@link GrinderProperties} instance
	 */
	public GrinderProperties getGrinderProperties(ApiTestRun perfTest) {
		return getGrinderProperties(perfTest, new NullScriptHandler());
	}


	public GrinderProperties getGrinderProperties(ApiTestRun perfTest, ScriptHandler scriptHandler) {
		try {
			// Use default properties first
			GrinderProperties grinderProperties = new GrinderProperties(config.getHome().getDefaultGrinderProperties());

			User user = perfTest.getCreatedUser();
			ApiRunTestConfig testConfig=perfTest.getRunConfig();
			// Get all files in the script path
			String scriptName = testConfig.getScriptName();
			FileEntry userDefinedGrinderProperties = fileEntryService.getOne(user,
					FilenameUtils.concat(FilenameUtils.getPath(scriptName), DEFAULT_GRINDER_PROPERTIES), -1L);
			if (!config.isSecurityEnabled() && userDefinedGrinderProperties != null) {
				// Make the property overridden by user property.
				GrinderProperties userProperties = new GrinderProperties();
				userProperties.load(new StringReader(userDefinedGrinderProperties.getContent()));
				grinderProperties.putAll(userProperties);
			}
			grinderProperties.setAssociatedFile(new File(DEFAULT_GRINDER_PROPERTIES));
			grinderProperties.setProperty(GRINDER_PROP_SCRIPT, scriptHandler.getScriptExecutePath(scriptName));

			grinderProperties.setProperty(GRINDER_PROP_TEST_ID, "test_" + perfTest.getId());
			grinderProperties.setInt(GRINDER_PROP_AGENTS, getSafe(testConfig.getAgentCount()));
			grinderProperties.setInt(GRINDER_PROP_PROCESSES, getSafe(testConfig.getProcesses()));
			grinderProperties.setInt(GRINDER_PROP_THREAD, getSafe(testConfig.getThreads()));
			if (testConfig.isThresholdDuration()) {
				grinderProperties.setLong(GRINDER_PROP_DURATION, getSafe(testConfig.getDuration()));
				grinderProperties.setInt(GRINDER_PROP_RUNS, 0);
			} else {
				grinderProperties.setInt(GRINDER_PROP_RUNS, getSafe(testConfig.getRunCount()));
				if (grinderProperties.containsKey(GRINDER_PROP_DURATION)) {
					grinderProperties.remove(GRINDER_PROP_DURATION);
				}
			}
			grinderProperties.setProperty(GRINDER_PROP_ETC_HOSTS,
					StringUtils.defaultIfBlank(testConfig.getTargetHosts(), ""));
			grinderProperties.setBoolean(GRINDER_PROP_USE_CONSOLE, true);
			if (BooleanUtils.isTrue(testConfig.getUseRampUp())) {
				grinderProperties.setBoolean(GRINDER_PROP_THREAD_RAMPUP, testConfig.getRampUpType() == RampUp.THREAD);
				grinderProperties.setInt(GRINDER_PROP_PROCESS_INCREMENT, getSafe(testConfig.getRampUpStep()));
				grinderProperties.setInt(GRINDER_PROP_PROCESS_INCREMENT_INTERVAL,
						getSafe(testConfig.getRampUpIncrementInterval()));
				if (testConfig.getRampUpType() == RampUp.PROCESS) {
					grinderProperties.setInt(GRINDER_PROP_INITIAL_SLEEP_TIME, getSafe(testConfig.getRampUpInitSleepTime()));
				} else {
					grinderProperties.setInt(GRINDER_PROP_INITIAL_THREAD_SLEEP_TIME,
							getSafe(testConfig.getRampUpInitSleepTime()));
				}
				grinderProperties.setInt(GRINDER_PROP_INITIAL_PROCESS, getSafe(testConfig.getRampUpInitCount()));
			} else {
				grinderProperties.setInt(GRINDER_PROP_PROCESS_INCREMENT, 0);
			}
			grinderProperties.setInt(GRINDER_PROP_REPORT_TO_CONSOLE, 500);
			grinderProperties.setProperty(GRINDER_PROP_USER, testConfig.getCreatedUser().getUserId());
			grinderProperties.setProperty(GRINDER_PROP_JVM_CLASSPATH, getCustomClassPath(perfTest));
			grinderProperties.setInt(GRINDER_PROP_IGNORE_SAMPLE_COUNT, getSafe(testConfig.getIgnoreSampleCount()));
			grinderProperties.setBoolean(GRINDER_PROP_SECURITY, config.isSecurityEnabled());
			// For backward agent compatibility.
			// If the security is not enabled, pass it as jvm argument.
			// If enabled, pass it to grinder.param. In this case, I drop the
			// compatibility.
			if (StringUtils.isNotBlank(testConfig.getParam())) {
				String param = testConfig.getParam().replace("'", "\\'").replace(" ", "");
				if (config.isSecurityEnabled()) {
					grinderProperties.setProperty(GRINDER_PROP_PARAM, StringUtils.trimToEmpty(param));
				} else {
					String property = grinderProperties.getProperty(GRINDER_PROP_JVM_ARGUMENTS, "");
					property = property + " -Dparam=" + param + " ";
					grinderProperties.setProperty(GRINDER_PROP_JVM_ARGUMENTS, property);
				}
			}
			LOGGER.info("Grinder Properties : {} ", grinderProperties);
			return grinderProperties;
		} catch (Exception e) {
			throw processException("error while prepare grinder property for " + " ,source type", e);
		}
	}

	public ScriptHandler prepareDistribution(ApiTestRun perfTest) {
		File perfTestDistDirectory = getDistributionPath(perfTest);
		User user = perfTest.getCreatedUser();
		ApiRunTestConfig testConfig= perfTest.getRunConfig();

		FileEntry scriptEntry = checkNotNull(
				fileEntryService.getOne(user,
						checkNotEmpty(testConfig.getScriptName(), "perfTest should have script name"),
						getSafe(testConfig.getScriptRevision())), "script should exist");
		// Get all files in the script path
		ScriptHandler handler = scriptHandlerFactory.getHandler(scriptEntry);

		ProcessingResultPrintStream processingResult = new ProcessingResultPrintStream(new ByteArrayOutputStream());
		handler.prepareDist(perfTest.getId(), user, scriptEntry, perfTestDistDirectory, config.getControllerProperties(),
				processingResult);
		LOGGER.info("File write is completed in {}", perfTestDistDirectory);
		if (!processingResult.isSuccess()) {
			File logDir = new File(getLogFileDirectory(perfTest), "distribution_log.txt");
			try {
				FileUtils.writeByteArrayToFile(logDir, processingResult.getLogByteArray());
			} catch (IOException e) {
				noOp();
			}
			throw processException("Error while file distribution is prepared.");
		}
		return handler;
	}

	/**
	 * Get the process and thread policy java script.
	 *
	 * @return policy javascript
	 */
	public String getProcessAndThreadPolicyScript() {
		return config.getProcessAndThreadPolicyScript();
	}

	/**
	 * Get the optimal process and thread count.
	 *
	 * @param newVuser the count of virtual users per agent
	 * @return optimal process thread count
	 */
	public ProcessAndThread calcProcessAndThread(int newVuser) {
		try {
			String script = getProcessAndThreadPolicyScript();
			ScriptEngine engine = new ScriptEngineManager().getEngineByName("javascript");
			engine.eval(script);
			int processCount = ((Double) engine.eval("getProcessCount(" + newVuser + ")")).intValue();
			int threadCount = ((Double) engine.eval("getThreadCount(" + newVuser + ")")).intValue();
			return new ProcessAndThread(processCount, threadCount);
		} catch (ScriptException e) {
			LOGGER.error("Error occurs while calc process and thread", e);
		}
		return new ProcessAndThread(1, 1);
	}

	/**
	 * get the data point interval of report data. Use dataPointCount / imgWidth as the interval. if interval is 1, it
	 * means we will get all point from report. If interval is 2, it means we will get 1 point from every 2 data.
	 *
	 * @param testId   test id
	 * @param dataType data type
	 * @param imgWidth image width
	 * @return interval interval value
	 */
	public int getReportDataInterval(long testId, String dataType, int imgWidth) {
		int pointCount = Math.max(imgWidth, MAX_POINT_COUNT);
		File reportFolder = config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
		int interval = 0;
		File targetFile = new File(reportFolder, dataType + DATA_FILE_EXTENSION);
		if (!targetFile.exists()) {
			LOGGER.warn("Report {} for test {} does not exist.", dataType, testId);
			return 0;
		}
		LineNumberReader lnr = null;

		FileInputStream in = null;
		InputStreamReader isr = null;
		try {
			in = new FileInputStream(targetFile);
			isr = new InputStreamReader(in);
			lnr = new LineNumberReader(isr);
			lnr.skip(targetFile.length());
			int lineNumber = lnr.getLineNumber() + 1;
			interval = Math.max(lineNumber / pointCount, 1);
		} catch (Exception e) {
			LOGGER.error("Failed to get report data for {}", dataType, e);
		} finally {
			IOUtils.closeQuietly(lnr);
			IOUtils.closeQuietly(isr);
			IOUtils.closeQuietly(in);
		}

		return interval;
	}

	public File getCsvReportFile(ApiTestRun perfTest) {
		return config.getHome().getPerfTestCsvFile(perfTest);
	}

	/**
	 * Get log file names for give test id.
	 *
	 * @param testId   test id
	 * @param fileName file name of one logs of the test
	 * @return file report file path
	 */
	public File getLogFile(long testId, String fileName) {
		return new File(getLogFileDirectory(String.valueOf(testId)), fileName);
	}


	public File getLogFileDirectory(ApiTestRun perfTest) {
		return config.getHome().getPerfTestLogDirectory(perfTest);
	}

	/**
	 * Get report file directory for give test id.
	 *
	 * @param testId test id
	 * @return logDir log file path of the test
	 */

	public File getLogFileDirectory(String testId) {
		return config.getHome().getPerfTestLogDirectory(testId);
	}

	/**
	 * Get log files list on the given test is.
	 *
	 * @param testId testId
	 * @return logFilesList log file list of that test
	 */
	public List<String> getLogFiles(long testId) {
		File logFileDirectory = getLogFileDirectory(String.valueOf(testId));
		if (!logFileDirectory.exists() || !logFileDirectory.isDirectory()) {
			return Collections.emptyList();
		}
		return Arrays.asList(logFileDirectory.list());
	}

	public File getReportFileDirectory(ApiTestRun perfTest) {
		return config.getHome().getPerfTestReportDirectory(perfTest);
	}

	/**
	 * To save statistics data when test is running and put into cache after that. If the console is not available, it
	 * returns null.
	 *
	 * @param singleConsole single console.
	 * @param perfTestId    perfTest Id
	 */
	@Transactional
	public void saveStatistics(SingleConsole singleConsole, Long perfTestId) {
		String runningSample = getProperSizeRunningSample(singleConsole);
		String agentState = getProperSizedStatusString(singleConsole);
		updateRuntimeStatistics(perfTestId, runningSample, agentState);
	}

	private String getProperSizeRunningSample(SingleConsole singleConsole) {
		Map<String, Object> statisticData = singleConsole.getStatisticsData();
		String runningSample = gson.toJson(statisticData);

		if (runningSample.length() > 9950) { // max column size is 10,000
			Map<String, Object> tempData = newHashMap();
			for (Entry<String, Object> each : statisticData.entrySet()) {
				String key = each.getKey();
				if (key.equals("totalStatistics") || key.equals("cumulativeStatistics")
						|| key.equals("lastSampleStatistics")) {
					continue;
				}
				tempData.put(key, each.getValue());
			}
			runningSample = gson.toJson(tempData);
		}
		return runningSample;
	}

	/**
	 * Get the limited size of agent status json string.
	 *
	 * @param singleConsole console which is connecting agents
	 * @return converted json
	 */
	public String getProperSizedStatusString(SingleConsole singleConsole) {
		Map<String, SystemDataModel> agentStatusMap = Maps.newHashMap();
		final int singleConsolePort = singleConsole.getConsolePort();
		for (AgentStatus each : agentManager.getAgentStatusSetConnectingToPort(singleConsolePort)) {
			agentStatusMap.put(each.getAgentName(), each.getSystemDataModel());
		}
		return getProperSizedStatusString(agentStatusMap);
	}

	String getProperSizedStatusString(Map<String, SystemDataModel> agentStatusMap) {
		String json = gson.toJson(agentStatusMap);
		int statusLength = StringUtils.length(json);
		if (statusLength > 9950) { // max column size is 10,000
			LOGGER.info("Agent status string length: {}, too long to save into table.", statusLength);
			double ratio = 9900.0 / statusLength;
			int pickSize = (int) (agentStatusMap.size() * ratio);
			Map<String, SystemDataModel> pickAgentStateMap = Maps.newHashMap();

			int pickIndex = 0;
			for (Entry<String, SystemDataModel> each : agentStatusMap.entrySet()) {
				if (pickIndex < pickSize) {
					pickAgentStateMap.put(each.getKey(), each.getValue());
					pickIndex++;
				}
			}
			json = gson.toJson(pickAgentStateMap);
			LOGGER.debug("Agent status string get {} outof {} agents, new size is {}.", new Object[]{pickSize,
					agentStatusMap.size(), json.length()});
		}
		return json;
	}


	@SuppressWarnings("unchecked")
	public Map<String, Object> getStatistics(ApiTestRun perfTest) {
		return gson.fromJson(perfTest.getRunningSample(), HashMap.class);
	}


	private Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

	@SuppressWarnings({"unchecked", "rawtypes"})
	public Map<String, HashMap> getAgentStat(ApiTestRun perfTest) {
		return gson.fromJson(perfTest.getAgentState(), HashMap.class);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ngrinder.service.IPerfTestService#getAllPerfTest()
	 */
	
	public List<ApiTestRun> getAllPerfTest() {
		return apiTestRunDao.findAll();
	}

	/**
	 * Create {@link ConsoleProperties} based on given {@link ApiTestRun} instance.
	 *
	 * @param perfTest perfTest
	 * @return {@link ConsoleProperties}
	 */
	public ConsoleProperties createConsoleProperties(ApiTestRun perfTest) {
		ConsoleProperties consoleProperties = ConsolePropertiesFactory.createEmptyConsoleProperties();
		ApiRunTestConfig testConfig=perfTest.getRunConfig();
		try {
			consoleProperties.setAndSaveDistributionDirectory(new Directory(getDistributionPath(perfTest)));
			consoleProperties.setConsoleHost(config.getCurrentIP());
			consoleProperties.setIgnoreSampleCount(getSafe(testConfig.getIgnoreSampleCount()));
			consoleProperties.setSampleInterval(1000 * getSafe(testConfig.getSamplingInterval()));
		} catch (Exception e) {
			throw processException("Error while setting console properties", e);
		}
		return consoleProperties;
	}

	double parseDoubleWithSafety(Map<?, ?> map, Object key, Double defaultValue) {
		Double doubleValue = MapUtils.getDouble(map, key, defaultValue);
		return Math.round(doubleValue * 100D) / 100D;
	}

	@SuppressWarnings("unchecked")
	public boolean hasTooManyError(ApiTestRun perfTest) {
		Map<String, Object> result = getStatistics(perfTest);
		Map<String, Object> totalStatistics = MapUtils.getMap(result, "totalStatistics", MapUtils.EMPTY_MAP);
		long tests = MapUtils.getDouble(totalStatistics, "Tests", 0D).longValue();
		long errors = MapUtils.getDouble(totalStatistics, "Errors", 0D).longValue();
		return ((((double) errors) / (tests + errors)) > 0.3d);
	}

	/**
	 * Update the given {@link PerfTest} properties after test finished.
	 *
	 * @param perfTest perfTest
	 */
	public void updatePerfTestAfterTestFinish(ApiTestRun perfTest) {
		TestReport report=perfTest.getReport();

		checkNotNull(perfTest);
		Map<String, Object> result = consoleManager.getConsoleUsingPort(perfTest.getPort()).getStatisticsData();

		@SuppressWarnings("unchecked")
		Map<String, Object> totalStatistics = MapUtils.getMap(result, "totalStatistics", MapUtils.EMPTY_MAP);
		LOGGER.info("Total Statistics for test {}  is {}", perfTest.getId(), totalStatistics);
		report.setAvgTps(parseDoubleWithSafety(totalStatistics, "TPS", 0D));
		report.setAvgRT(parseDoubleWithSafety(totalStatistics, "Mean_Test_Time_(ms)", 0D));
		report.setMaxTps(parseDoubleWithSafety(totalStatistics, "Peak_TPS", 0D));
		report.setTransactions(MapUtils.getDouble(totalStatistics, "Tests", 0D).longValue());
		report.setErrors(MapUtils.getDouble(totalStatistics, "Errors", 0D).longValue());

		reportService.save(report);
	}

	/**
	 * Get maximum concurrent test count.
	 *
	 * @return maximum concurrent test
	 */
	public int getMaximumConcurrentTestCount() {
		return config.getControllerProperties().getPropertyInt(PROP_CONTROLLER_MAX_CONCURRENT_TEST);
	}

	/**
	 * Check the test can be executed more.
	 *
	 * @return true if possible
	 * @deprecated
	 */
	@SuppressWarnings("UnusedDeclaration")
	public boolean canExecuteTestMore() {
		return count(null, Status.getProcessingOrTestingTestStatus()) < getMaximumConcurrentTestCount();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ngrinder.service.IPerfTestService#stop(org.ngrinder .model.User, java.lang.Long)
	 */
	
	@Transactional
	public void stop(User user, Long id) {
		ApiTestRun perfTest = getOne(user, id);
		// If it's not requested by user who started job. It's wrong request.
		if (!hasPermission(perfTest, user, Permission.STOP_TEST_OF_OTHER)) {
			return;
		}
		// If it's not stoppable status.. It's wrong request.
		if (!perfTest.getStatus().isStoppable()) {
			return;
		}
		// Just mark cancel on console
		// This will be not be effective on cluster mode.
		consoleManager.getConsoleUsingPort(perfTest.getPort()).cancel();
		perfTest.setStopRequest(true);
		apiTestRunDao.save(perfTest);
	}

	/**
	 * Check if given user has a permission on perftest.
	 *
	 * @param perfTest perf test
	 * @param user     user
	 * @param type     permission type to check
	 * @return true if it has
	 */
	public boolean hasPermission(ApiTestRun perfTest, User user, Permission type) {
		return perfTest != null && (user.getRole().hasPermission(type) || user.equals(perfTest.getCreatedUser()));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ngrinder.service.IPerfTestService#getAllStopRequested()
	 */
	
	public List<ApiTestRun> getAllStopRequested() {
		final List<ApiTestRun> perfTests = getAll(null, config.getRegion(), getProcessingOrTestingTestStatus());
		CollectionUtils.filter(perfTests, new Predicate() {

			public boolean evaluate(Object object) {
				return (((ApiTestRun) object).getStopRequest() == Boolean.TRUE);
			}
		});
		return perfTests;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.ngrinder.service.IPerfTestService#addCommentOn(org.ngrinder .model.User, int, java.lang.String)
	 */
	@Transactional
	public void addCommentOn(User user, Long testId, String testComment, String tagString) {
		ApiTestRun perfTest = getOne(user, testId);
		perfTest.setTestComment(testComment);
		apiTestRunDao.save(perfTest);
	}

	/**
	 * get current running test status, which is, how many user run how many tests with some agents.
	 *
	 * @return PerfTestStatisticsList PerfTestStatistics list
	 */
	@Cacheable("current_perftest_statistics")
	@Transactional
	public Collection<PerfTestStatistics> getCurrentPerfTestStatistics() {
		Map<User, PerfTestStatistics> perfTestPerUser = newHashMap();
		for (ApiTestRun each : getAll(null, getProcessingOrTestingTestStatus())) {
			User lastModifiedUser = each.getCreatedUser().getUserBaseInfo();
			PerfTestStatistics perfTestStatistics = perfTestPerUser.get(lastModifiedUser);
			if (perfTestStatistics == null) {
				perfTestStatistics = new PerfTestStatistics(lastModifiedUser);
				perfTestPerUser.put(lastModifiedUser, perfTestStatistics);
			}
			perfTestStatistics.addPerfTest(each);
		}
		return perfTestPerUser.values();
	}

	/**
	 * Get PerfTest directory in which {@link PerfTest} related files are saved.
	 *
	 * @param perfTest perfTest
	 * @return directory
	 */
	
	public File getPerfTestDirectory(ApiTestRun perfTest) {
		return config.getHome().getPerfTestDirectory(perfTest);
	}

	/**
	 * Delete All PerfTests and related tags belonging to given user.
	 *
	 * @param user user
	 * @return deleted {@link PerfTest} list
	 */
	@Transactional
	public List<ApiTestRun> deleteAll(User user) {
		List<ApiTestRun> perfTestList = getAll(user);

		apiTestRunDao.save(perfTestList);
		apiTestRunDao.flush();
		apiTestRunDao.delete(perfTestList);
		apiTestRunDao.flush();
		return perfTestList;
	}


	public void newTestRun(ApiTestRun run){
		apiTestRunDao.saveAndFlush(run);
	}

	public void deleteTestRun(long id){
		apiTestRunDao.delete(id);
	}

	public ApiTestRun findById(long id){
		return apiTestRunDao.findById(id);
	}


	public Config getConfig() {
		return config;
	}

	public void setConfig(Config config) {
		this.config = config;
	}


	public void cleanUpDistFolder(ApiTestRun perfTest) {
		FileUtils.deleteQuietly(getDistributionPath(perfTest));
	}



	public ApiTestRun save(ApiTestRun perfTest) {
		checkNotNull(perfTest);
		// Merge if necessary
		if (perfTest.exist()) {
			ApiTestRun existingPerfTest = apiTestRunDao.findOne(perfTest.getId());
			perfTest = existingPerfTest.merge(perfTest);
		} else {
			perfTest.clearMessages();
		}
		return apiTestRunDao.saveAndFlush(perfTest);
	}

	public void cleanUpRuntimeOnlyData(ApiTestRun perfTest) {
		perfTest.setRunningSample("");
		perfTest.setAgentState("");
		perfTest.setMonitorState("");
		save(perfTest);
	}

	/**
	 * Put the given {@link org.ngrinder.monitor.share.domain.SystemInfo} maps into the given perftest entity.
	 *
	 * @param perfTestId  id of perf test
	 * @param systemInfos systemDataModel map
	 */
	@Transactional
	public void updateMonitorStat(Long perfTestId, Map<String, SystemDataModel> systemInfos) {
		String json = gson.toJson(systemInfos);
		if (json.length() >= 2000) {
			Map<String, SystemDataModel> systemInfo = Maps.newHashMap();
			int i = 0;
			for (Entry<String, SystemDataModel> each : systemInfos.entrySet()) {
				if (i++ > 3) {
					break;
				}
				systemInfo.put(each.getKey(), each.getValue());
			}
			json = gson.toJson(systemInfo);
		}
		apiTestRunDao.updatetMonitorStatus(perfTestId, json);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public Map<String, HashMap> getMonitorStat(ApiTestRun perfTest) {
		return gson.fromJson(perfTest.getMonitorState(), HashMap.class);
	}

	/**
	 * Get the monitor data interval value. In the normal, the image width is 700, and if the data count is too big,
	 * there will be too many points in the chart. So we will calculate the interval to get appropriate count of data to
	 * display. For example, interval value "2" means, get one record for every "2" records.
	 *
	 * @param testId     test id
	 * @param targetIP   ip address of monitor target
	 * @param imageWidth image with of the chart.
	 * @return interval value.
	 */
	public int getMonitorGraphInterval(long testId, String targetIP, int imageWidth) {
		File monitorDataFile = new File(config.getHome().getPerfTestReportDirectory(String.valueOf(testId)),
				MONITOR_FILE_PREFIX + targetIP + ".data");

		int pointCount = Math.max(imageWidth, MAX_POINT_COUNT);
		FileInputStream in = null;
		InputStreamReader isr = null;
		LineNumberReader lnr = null;
		int interval = 0;
		try {
			in = new FileInputStream(monitorDataFile);
			isr = new InputStreamReader(in);
			lnr = new LineNumberReader(isr);
			lnr.skip(monitorDataFile.length());
			int lineNumber = lnr.getLineNumber() + 1;
			interval = Math.max(lineNumber / pointCount, 1);
		} catch (FileNotFoundException e) {
			LOGGER.info("Monitor data file does not exist at {}", monitorDataFile);
		} catch (IOException e) {
			LOGGER.info("Error while getting monitor:{} data file:{}", targetIP, monitorDataFile);
		} finally {
			IOUtils.closeQuietly(lnr);
			IOUtils.closeQuietly(isr);
			IOUtils.closeQuietly(in);
		}
		return interval;
	}

	/**
	 * Get system monitor data and wrap the data as a string value like "[22,11,12,34,....]", which can be used directly
	 * in JS as a vector.
	 *
	 * @param testId       test id
	 * @param targetIP     ip address of the monitor target
	 * @param dataInterval interval value to get data. Interval value "2" means, get one record for every "2" records.
	 * @return return the data in map
	 */
	public Map<String, String> getMonitorGraph(long testId, String targetIP, int dataInterval) {
		Map<String, String> returnMap = Maps.newHashMap();
		File monitorDataFile = new File(config.getHome().getPerfTestReportDirectory(String.valueOf(testId)),
				MONITOR_FILE_PREFIX + targetIP + ".data");
		BufferedReader br = null;
		try {

			StringBuilder sbUsedMem = new StringBuilder("[");
			StringBuilder sbCPUUsed = new StringBuilder("[");
			StringBuilder sbNetReceived = new StringBuilder("[");
			StringBuilder sbNetSent = new StringBuilder("[");
			StringBuilder customData1 = new StringBuilder("[");
			StringBuilder customData2 = new StringBuilder("[");
			StringBuilder customData3 = new StringBuilder("[");
			StringBuilder customData4 = new StringBuilder("[");
			StringBuilder customData5 = new StringBuilder("[");

			br = new BufferedReader(new FileReader(monitorDataFile));
			br.readLine(); // skip the header.
			// "ip,system,collectTime,freeMemory,totalMemory,cpuUsedPercentage,receivedPerSec,sentPerSec"
			String line = br.readLine();
			int skipCount = dataInterval;
			// to be compatible with previous version, check the length before
			// adding
			while (StringUtils.isNotBlank(line)) {
				if (skipCount < dataInterval) {
					skipCount++;
				} else {
					skipCount = 1;
					String[] datalist = StringUtils.split(line, ",");
					if ("null".equals(datalist[4]) || "undefined".equals(datalist[4])) {
						sbUsedMem.append("null").append(",");
					} else {
						sbUsedMem.append(Long.valueOf(datalist[4]) - Long.valueOf(datalist[3])).append(",");
					}
					addCustomData(sbCPUUsed, 5, datalist);
					addCustomData(sbNetReceived, 6, datalist);
					addCustomData(sbNetSent, 7, datalist);
					addCustomData(customData1, 8, datalist);
					addCustomData(customData2, 9, datalist);
					addCustomData(customData3, 10, datalist);
					addCustomData(customData4, 11, datalist);
					addCustomData(customData5, 12, datalist);
					line = br.readLine();
				}
			}
			completeCustomData(returnMap, "cpu", sbCPUUsed);
			completeCustomData(returnMap, "memory", sbUsedMem);
			completeCustomData(returnMap, "received", sbNetReceived);
			completeCustomData(returnMap, "sent", sbNetSent);
			completeCustomData(returnMap, "customData1", customData1);
			completeCustomData(returnMap, "customData2", customData2);
			completeCustomData(returnMap, "customData3", customData3);
			completeCustomData(returnMap, "customData4", customData4);
			completeCustomData(returnMap, "customData5", customData5);
		} catch (IOException e) {
			LOGGER.info("Error while getting monitor {} data file at {}", targetIP, monitorDataFile);
		} finally {
			IOUtils.closeQuietly(br);
		}
		return returnMap;
	}


	private void addCustomData(StringBuilder customData, int index, String[] data) {
		if (data.length > index) {
			customData.append(data[index]).append(",");
		}
	}

	private void completeCustomData(Map<String, String> returnMap, String key, StringBuilder customData) {
		if (customData.charAt(customData.length() - 1) == ',') {
			customData.deleteCharAt(customData.length() - 1);
		}
		returnMap.put(key, customData.append("]").toString());
	}


	/**
	 * Get report file directory for give test id .
	 *
	 * @param testId testId
	 * @return reportDir report file path
	 */
	public File getReportFileDirectory(long testId) {
		return config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
	}

	/**
	 * Get interval value of the monitor data of a plugin, like jvm monitor plugin.
	 * The usage of interval value is same as system monitor data.
	 *
	 * @param testId     test id
	 * @param plugin     plugin name
	 * @param kind       plugin kind
	 * @param imageWidth image with of the chart.
	 * @return interval value.
	 */
	public int getReportPluginGraphInterval(long testId, String plugin, String kind, int imageWidth) {
		return getRecordInterval(imageWidth, getReportPluginDataFile(testId, plugin, kind));
	}

	/**
	 * Get available report plugins list for the given test.
	 *
	 * @param testId test id
	 * @return plugin names
	 */
	public List<Pair<String, String>> getAvailableReportPlugins(Long testId) {
		List<Pair<String, String>> result = newArrayList();
		File reportDir = getReportFileDirectory(testId);
		if (reportDir.exists()) {
			for (File plugin : checkNotNull(reportDir.listFiles())) {
				if (plugin.isDirectory()) {
					for (String kind : checkNotNull(plugin.list())) {
						if (kind.endsWith(".data")) {
							result.add(Pair.of(plugin.getName(), FilenameUtils.getBaseName(kind)));
						}
					}
				}
			}
		}
		return result;
	}

	/*
	 * Plugin monitor data should be {TestReportDir}/{plugin}/{kind}.data
	 */
	private File getReportPluginDataFile(Long testId, String plugin, String kind) {
		File reportDir = getReportFileDirectory(testId);
		File pluginDir = new File(reportDir, plugin);
		return new File(pluginDir, kind + ".data");
	}

	/*
	 * Get the interval value. In the normal, the image width is 700, and if the data count is too big,
	 * there will be too many points in the chart. So we will calculate the interval to get appropriate count of data to
	 * display. For example, interval value "2" means, get one record for every "2" records.
	 */
	private int getRecordInterval(int imageWidth, File dataFile) {
		int pointCount = Math.max(imageWidth, MAX_POINT_COUNT);
		FileInputStream in = null;
		InputStreamReader isr = null;
		LineNumberReader lnr = null;
		int interval = 0;
		try {
			in = new FileInputStream(dataFile);
			isr = new InputStreamReader(in);
			lnr = new LineNumberReader(isr);
			lnr.skip(dataFile.length());
			interval = Math.max((lnr.getLineNumber() + 1) / pointCount, 1);
		} catch (FileNotFoundException e) {
			LOGGER.error("data file not exist:{}", dataFile);
			LOGGER.error(e.getMessage(), e);
		} catch (IOException e) {
			LOGGER.error("Error while getting data file:{}", dataFile);
			LOGGER.error(e.getMessage(), e);
		} finally {
			IOUtils.closeQuietly(lnr);
			IOUtils.closeQuietly(isr);
			IOUtils.closeQuietly(in);
		}
		return interval;
	}

	/**
	 * Get plugin monitor data and wrap the data as a string value like "[22,11,12,34,....]", which can be used directly
	 * in JS as a vector.
	 *
	 * @param testId   test id
	 * @param plugin   plugin name
	 * @param kind     kind
	 * @param interval interval value to get data. Interval value "2" means, get one record for every "2" records.
	 * @return return the data in map
	 */
	public Map<String, Object> getReportPluginGraph(long testId, String plugin, String kind, int interval) {
		Map<String, Object> returnMap = Maps.newHashMap();
		File pluginDataFile = getReportPluginDataFile(testId, plugin, kind);
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(pluginDataFile));
			String header = br.readLine();

			StringBuilder headerSB = new StringBuilder("[");
			String[] headers = StringUtils.split(header, ",");
			String[] refinedHeaders = StringUtils.split(header, ",");
			List<StringBuilder> dataStringBuilders = new ArrayList<StringBuilder>(headers.length);

			for (int i = 0; i < headers.length; i++) {
				dataStringBuilders.add(new StringBuilder("["));
				String refinedHead = headers[i].trim().replaceAll(" ", "_");
				refinedHeaders[i] = refinedHead;
				headerSB.append("'").append(refinedHead).append("'").append(",");
			}
			String headerStringInJSONList = headerSB.deleteCharAt(headerSB.length() - 1).append("]").toString();
			returnMap.put("header", headerStringInJSONList);

			String line = br.readLine();
			int skipCount = interval;
			// to be compatible with previous version, check the length before adding
			while (StringUtils.isNotBlank(line)) {
				if (skipCount < interval) {
					skipCount++;
				} else {
					skipCount = 1;
					String[] records = StringUtils.split(line, ",");
					for (int i = 0; i < records.length; i++) {
						if ("null".equals(records[i]) || "undefined".equals(records[i])) {
							dataStringBuilders.get(i).append("null").append(",");
						} else {
							dataStringBuilders.get(i).append(records[i]).append(",");
						}
					}
					line = br.readLine();
				}
			}
			for (int i = 0; i < refinedHeaders.length; i++) {
				StringBuilder dataSB = dataStringBuilders.get(i);
				if (dataSB.charAt(dataSB.length() - 1) == ',') {
					dataSB.deleteCharAt(dataSB.length() - 1);
				}
				dataSB.append("]");
				returnMap.put(refinedHeaders[i], dataSB.toString());
			}
		} catch (IOException e) {
			LOGGER.error("Error while getting monitor: {} data file:{}", plugin, pluginDataFile);
			LOGGER.error(e.getMessage(), e);
		} finally {
			IOUtils.closeQuietly(br);
		}
		return returnMap;
	}


	/**
	 * Get json string that contains test report data as a json string.
	 *
	 * @param testId   test id
	 * @param key      key
	 * @param interval interval to collect data
	 * @return json list
	 */
	public String getSingleReportDataAsJson(long testId, String key, int interval) {
		File reportDataFile = getReportDataFile(testId, key);
		return getFileDataAsJson(reportDataFile, interval);
	}

	/**
	 * Get list that contains test report data as a string.
	 *
	 * @param testId   test id
	 * @param key      report key
	 * @param onlyTotal true if only total show be passed
	 * @param interval interval to collect data
	 * @return list containing label and tps value list
	 */
	public Pair<ArrayList<String>, ArrayList<String>> getReportData(long testId, String key, boolean onlyTotal, int interval) {
		Pair<ArrayList<String>, ArrayList<String>> resultPair = Pair.of(new ArrayList<String>(),
				new ArrayList<String>());
		List<File> reportDataFiles = onlyTotal ? Lists.newArrayList(getReportDataFile(testId, key)) : getReportDataFiles(testId, key);
		for (File file : reportDataFiles) {
			String buildReportName = buildReportName(key, file);
			if (key.equals(buildReportName)) {
				buildReportName = "Total";
			} else {
				buildReportName = buildReportName.replace("_", " ");
			}
			resultPair.getFirst().add(buildReportName);
			resultPair.getSecond().add(getFileDataAsJson(file, interval));
		}
		return resultPair;
	}

	private String buildReportName(String key, File file) {
		String reportName = FilenameUtils.removeExtension(file.getName());
		if (key.equals(reportName)) {
			return reportName;
		}
		String[] baseName = StringUtils.split(reportName, "-", 2);
		if (SingleConsole.INTERESTING_PER_TEST_STATISTICS.contains(baseName[0]) && baseName.length >= 2) {
			reportName = baseName[1];
		}
		return reportName;
	}

	/**
	 * Get a single file for the given report key.
	 *
	 * @param testId test id
	 * @param key    key
	 * @return return file
	 */
	public File getReportDataFile(long testId, String key) {
		File reportFolder = config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
		return new File(reportFolder, key + ".data");
	}

	/**
	 * Get files respectively if there are multiple tests.
	 *
	 * @param testId test id
	 * @param key    report key
	 * @return return file list
	 */
	public List<File> getReportDataFiles(long testId, String key) {
		File reportFolder = config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
		FileFilter fileFilter = new WildcardFileFilter(key + "*.data");
		File[] files = reportFolder.listFiles(fileFilter);
		Arrays.sort(files, new Comparator<File>() {
			
			public int compare(File o1, File o2) {
				return FilenameUtils.getBaseName(o1.getName()).compareTo(FilenameUtils.getBaseName(o2.getName()));
			}
		});
		return Arrays.asList(files);
	}

	/**
	 * Get the test report data as a json string.
	 *
	 * @param targetFile target file
	 * @param interval   interval to collect data
	 * @return json string
	 */
	private String getFileDataAsJson(File targetFile, int interval) {
		if (!targetFile.exists()) {
			return "[]";
		}
		StringBuilder reportData = new StringBuilder("[");
		FileReader reader = null;
		BufferedReader br = null;
		try {
			reader = new FileReader(targetFile);
			br = new BufferedReader(reader);
			String data = br.readLine();
			int current = 0;
			while (StringUtils.isNotBlank(data)) {
				if (0 == current) {
					reportData.append(data);
					reportData.append(",");
				}
				if (++current >= interval) {
					current = 0;
				}
				data = br.readLine();
			}
			if (reportData.charAt(reportData.length() - 1) == ',') {
				reportData.deleteCharAt(reportData.length() - 1);
			}
		} catch (IOException e) {
			LOGGER.error("Report data retrieval is failed: {}", e.getMessage());
			LOGGER.debug("Trace is : ", e);
		} finally {
			IOUtils.closeQuietly(reader);
			IOUtils.closeQuietly(br);
		}
		return reportData.append("]").toString();
	}


}