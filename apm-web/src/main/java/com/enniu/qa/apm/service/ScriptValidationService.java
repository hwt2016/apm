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
package com.enniu.qa.apm.service;

import com.enniu.qa.apm.configuration.Config;
import com.enniu.qa.apm.handler.ProcessingResultPrintStream;
import com.enniu.qa.apm.handler.ScriptHandler;
import com.enniu.qa.apm.handler.ScriptHandlerFactory;
import com.enniu.qa.apm.model.FileEntry;
import net.grinder.engine.agent.LocalScriptTestDriveService;
import net.grinder.util.thread.Condition;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.StringUtils;
import org.ngrinder.common.util.Preconditions;
import org.ngrinder.model.IFileEntry;
import org.ngrinder.model.User;

import org.ngrinder.service.AbstractScriptValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotNull;
import static com.enniu.qa.apm.util.TypeConvertUtils.cast;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static com.enniu.qa.apm.configuration.constant.ControllerConstants.PROP_CONTROLLER_VALIDATION_SYNTAX_CHECK;
import static com.enniu.qa.apm.configuration.constant.ControllerConstants.PROP_CONTROLLER_VALIDATION_TIMEOUT;
/**
 * Script Validation Service.
 *
 * @author JunHo Yoon
 * @since 3.0
 */

@Service
public class ScriptValidationService extends AbstractScriptValidationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ScriptValidationService.class);

	@Autowired
	private LocalScriptTestDriveService localScriptTestDriveService;

	@Autowired
	private FileEntryService fileEntryService;

	@Autowired
	private Config config;

	@Autowired
	private ScriptHandlerFactory scriptHandlerFactory;

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.ngrinder.script.service.IScriptValidationService#validate(org
	 * .ngrinder.model.User, org.ngrinder.model.IFileEntry, boolean,
	 * java.lang.String)
	 */
	@Override
	public String validate(User user, IFileEntry scriptIEntry, boolean useScriptInSVN, String hostString) {
		FileEntry scriptEntry = cast(scriptIEntry);
		try {
			checkNotNull(scriptEntry, "scriptEntity should be not null");
			checkNotEmpty(scriptEntry.getPath(), "scriptEntity path should be provided");
			if (!useScriptInSVN) {
				checkNotEmpty(scriptEntry.getContent(), "scriptEntity content should be provided");
			}
			checkNotNull(user, "user should be provided");
			// String result = checkSyntaxErrors(scriptEntry.getContent());

			ScriptHandler handler = scriptHandlerFactory.getHandler(scriptEntry);
			if (config.getControllerProperties().getPropertyBoolean(PROP_CONTROLLER_VALIDATION_SYNTAX_CHECK)) {
				String result = handler.checkSyntaxErrors(scriptEntry.getPath(), scriptEntry.getContent());
				LOGGER.info("Perform Syntax Check by {} for {}", user.getUserId(), scriptEntry.getPath());
				if (result != null) {
					return result;
				}
			}
			File scriptDirectory = config.getHome().getScriptDirectory(user);
			FileUtils.deleteDirectory(scriptDirectory);
			Preconditions.checkTrue(scriptDirectory.mkdirs(), "Script directory {} creation is failed.");

			ProcessingResultPrintStream processingResult = new ProcessingResultPrintStream(new ByteArrayOutputStream());
			handler.prepareDist(0L, user, scriptEntry, scriptDirectory, config.getControllerProperties(), processingResult);
			if (!processingResult.isSuccess()) {
				return new String(processingResult.getLogByteArray());
			}
			File scriptFile = new File(scriptDirectory, FilenameUtils.getName(scriptEntry.getPath()));

			if (useScriptInSVN) {
				fileEntryService.writeContentTo(user, scriptEntry.getPath(), scriptDirectory);
			} else {
				FileUtils.writeStringToFile(scriptFile, scriptEntry.getContent(),
						StringUtils.defaultIfBlank(scriptEntry.getEncoding(), "UTF-8"));
			}
			File doValidate = localScriptTestDriveService.doValidate(scriptDirectory, scriptFile, new Condition(),
					config.isSecurityEnabled(), hostString, getTimeout());
			List<String> readLines = FileUtils.readLines(doValidate);
			StringBuilder output = new StringBuilder();
			String path = config.getHome().getDirectory().getAbsolutePath();
			for (String each : readLines) {
				if (!each.startsWith("*sys-package-mgr")) {
					each = each.replace(path, "${NGRINDER_HOME}");
					output.append(each).append("\n");
				}
			}
			return output.toString();
		} catch (Exception e) {
			throw processException(e);
		}
	}

	protected int getTimeout() {
		return Math.max(config.getControllerProperties().getPropertyInt(PROP_CONTROLLER_VALIDATION_TIMEOUT), 10);
	}
}