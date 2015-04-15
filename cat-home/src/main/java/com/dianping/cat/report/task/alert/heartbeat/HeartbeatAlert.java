package com.dianping.cat.report.task.alert.heartbeat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.unidal.helper.Threads.Task;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.util.StringUtils;
import org.unidal.tuple.Pair;

import com.dianping.cat.Cat;
import com.dianping.cat.Constants;
import com.dianping.cat.configuration.ServerConfigManager;
import com.dianping.cat.consumer.heartbeat.HeartbeatAnalyzer;
import com.dianping.cat.consumer.heartbeat.model.entity.HeartbeatReport;
import com.dianping.cat.consumer.heartbeat.model.entity.Machine;
import com.dianping.cat.consumer.heartbeat.model.entity.Period;
import com.dianping.cat.consumer.transaction.TransactionAnalyzer;
import com.dianping.cat.consumer.transaction.model.entity.TransactionReport;
import com.dianping.cat.helper.TimeHelper;
import com.dianping.cat.home.rule.entity.Condition;
import com.dianping.cat.home.rule.entity.Config;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.dianping.cat.report.task.alert.AlertResultEntity;
import com.dianping.cat.report.task.alert.AlertType;
import com.dianping.cat.report.task.alert.BaseAlert;
import com.dianping.cat.report.task.alert.sender.AlertEntity;
import com.dianping.cat.service.ModelRequest;
import com.dianping.cat.service.ModelResponse;
import com.dianping.cat.system.config.DisplayPolicyManager;

public class HeartbeatAlert extends BaseAlert implements Task {

	@Inject(type = ModelService.class, value = HeartbeatAnalyzer.ID)
	private ModelService<HeartbeatReport> m_service;

	@Inject(type = ModelService.class, value = TransactionAnalyzer.ID)
	private ModelService<TransactionReport> m_transactionService;

	@Inject
	private DisplayPolicyManager m_displayManager;

	@Inject
	private ServerConfigManager m_configManager;

	private HeartbeatReport m_lastReport;

	private HeartbeatReport m_currentReport;

	private static final String[] m_metrics = { "ThreadCount", "DaemonCount", "TotalStartedCount", "CatThreadCount",
	      "PiegonThreadCount", "HttpThreadCount", "NewGcCount", "OldGcCount", "MemoryFree", "HeapUsage",
	      "NoneHeapUsage", "SystemLoadAverage", "CatMessageOverflow", "CatMessageSize" };

	private void buildArray(Map<String, double[]> map, int index, String name, double value) {
		double[] array = map.get(name);
		if (array == null) {
			array = new double[60];
			map.put(name, array);
		}
		array[index] = value;
	}

	private void buildArrayForExtensions(Map<String, double[]> map, int index, Period period) {
		for (String groupName : m_displayManager.queryOrderedGroupNames()) {
			for (String metricName : m_displayManager.queryOrderedMetricNames(groupName)) {
				double[] array = map.get(metricName);

				if (array == null) {
					array = new double[60];
					map.put(metricName, array);
				}
				try {
					int unit = m_displayManager.queryUnit(groupName, metricName);
					array[index] = period.findExtension(groupName).findDetail(metricName).getValue() / unit;
				} catch (Exception e) {
					array[index] = 0;
				}
			}
		}
	}

	private void checkAndGenerateCurrentReport(String domain) {
		if (m_currentReport == null) {
			long currentMill = System.currentTimeMillis();
			long currentHourMill = currentMill - currentMill % TimeHelper.ONE_HOUR;

			m_currentReport = generateReport(domain, currentHourMill);
		}
	}

	private void checkAndGenerateLastReport(String domain) {
		if (m_lastReport == null) {
			long currentMill = System.currentTimeMillis();
			long lastHourMill = currentMill - currentMill % TimeHelper.ONE_HOUR - TimeHelper.ONE_HOUR;

			m_lastReport = generateReport(domain, lastHourMill);
		}
	}

	private void clearCacheReport() {
		m_lastReport = null;
		m_currentReport = null;
	}

	private void convertDeltaExtensions(Map<String, double[]> map) {
		for (String groupName : m_displayManager.queryOrderedGroupNames()) {
			for (String metricName : m_displayManager.queryOrderedMetricNames(groupName)) {
				if (m_displayManager.isDelta(groupName, metricName)) {
					double[] sources = map.get(metricName);
					double[] targets = new double[60];

					for (int i = 1; i < 60; i++) {
						if (sources[i - 1] > 0) {
							double delta = sources[i] - sources[i - 1];

							if (delta >= 0) {
								targets[i] = delta;
							}
						}
					}
					map.put(metricName, targets);
				}
			}
		}
	}

	private void convertToDeltaArray(Map<String, double[]> map, String name) {
		double[] sources = map.get(name);
		double[] targets = new double[60];

		for (int i = 1; i < 60; i++) {
			if (sources[i - 1] > 0) {
				double delta = sources[i] - sources[i - 1];

				if (delta >= 0) {
					targets[i] = delta;
				}
			}
		}
		map.put(name, targets);
	}

	private double[] extract(double[] lastHourValues, double[] currentHourValues, int maxMinute, int alreadyMinute) {
		int lastLength = maxMinute - alreadyMinute - 1;
		double[] result = new double[maxMinute];

		for (int i = 0; i < lastLength; i++) {
			result[i] = lastHourValues[60 - lastLength + i];
		}
		for (int i = lastLength; i < maxMinute; i++) {
			result[i] = currentHourValues[i - lastLength];
		}
		return result;
	}

	private double[] extract(double[] values, int maxMinute, int alreadyMinute) {
		double[] result = new double[maxMinute];

		for (int i = 0; i < maxMinute; i++) {
			result[i] = values[alreadyMinute + 1 - maxMinute + i];
		}
		return result;
	}

	private Map<String, double[]> generateArgumentMap(Machine machine) {
		Map<String, double[]> map = new HashMap<String, double[]>();
		List<Period> periods = machine.getPeriods();

		for (int index = 0; index < periods.size(); index++) {
			Period period = periods.get(index);
			int minute = period.getMinute();

			buildArray(map, minute, "ThreadCount", period.getThreadCount());
			buildArray(map, minute, "DaemonCount", period.getDaemonCount());
			buildArray(map, minute, "TotalStartedCount", period.getTotalStartedCount());
			buildArray(map, minute, "CatThreadCount", period.getCatThreadCount());
			buildArray(map, minute, "PiegonThreadCount", period.getPigeonThreadCount());
			buildArray(map, minute, "HttpThreadCount", period.getHttpThreadCount());
			buildArray(map, minute, "NewGcCount", period.getNewGcCount());
			buildArray(map, minute, "OldGcCount", period.getOldGcCount());
			buildArray(map, minute, "MemoryFree", period.getMemoryFree());
			buildArray(map, minute, "HeapUsage", period.getHeapUsage());
			buildArray(map, minute, "NoneHeapUsage", period.getNoneHeapUsage());
			buildArray(map, minute, "SystemLoadAverage", period.getSystemLoadAverage());
			buildArray(map, minute, "CatMessageOverflow", period.getCatMessageOverflow());
			buildArray(map, minute, "CatMessageSize", period.getCatMessageSize());
			buildArrayForExtensions(map, minute, period);
		}
		convertToDeltaArray(map, "TotalStartedCount");
		convertToDeltaArray(map, "NewGcCount");
		convertToDeltaArray(map, "OldGcCount");
		convertToDeltaArray(map, "CatMessageSize");
		convertToDeltaArray(map, "CatMessageOverflow");
		convertDeltaExtensions(map);
		return map;
	}

	private HeartbeatReport generateReport(String domain, long date) {
		ModelRequest request = new ModelRequest(domain, date)//
		      .setProperty("ip", Constants.ALL);

		if (m_service.isEligable(request)) {
			ModelResponse<HeartbeatReport> response = m_service.invoke(request);

			return response.getModel();
		} else {
			throw new RuntimeException("Internal error: no eligable ip service registered for " + request + "!");
		}
	}

	private List<String> getAllMetrics() {
		List<String> metrics = new ArrayList<String>();

		metrics.addAll(Arrays.asList(m_metrics));
		metrics.addAll(m_displayManager.queryMetrics());
		return metrics;
	}

	@Override
	public String getName() {
		return AlertType.HeartBeat.getName();
	}

	private void processDomain(String domain) {
		clearCacheReport();
		int minute = getAlreadyMinute();

		for (String metric : getAllMetrics()) {
			List<Config> configs = m_ruleConfigManager.queryConfigs(domain, metric, null);
			Pair<Integer, List<Condition>> resultPair = queryCheckMinuteAndConditions(configs);
			int maxMinute = resultPair.getKey();
			List<Condition> conditions = resultPair.getValue();

			if (minute >= maxMinute - 1) {
				checkAndGenerateCurrentReport(domain);

				for (Machine machine : m_currentReport.getMachines().values()) {
					String ip = machine.getIp();
					double[] arguments = generateArgumentMap(machine).get(metric);
					double[] values = extract(arguments, maxMinute, minute);

					processMeitrc(domain, ip, metric, conditions, maxMinute, values);
				}
			} else if (minute < 0) {
				checkAndGenerateLastReport(domain);

				for (Machine machine : m_lastReport.getMachines().values()) {
					String ip = machine.getIp();
					double[] arguments = generateArgumentMap(machine).get(metric);
					double[] values = extract(arguments, maxMinute, 59);

					processMeitrc(domain, ip, metric, conditions, maxMinute, values);
				}
			} else {
				checkAndGenerateCurrentReport(domain);
				checkAndGenerateLastReport(domain);

				for (Machine lastMachine : m_lastReport.getMachines().values()) {
					String ip = lastMachine.getIp();
					Machine currentMachine = m_currentReport.getMachines().get(ip);

					if (currentMachine != null) {
						Map<String, double[]> lastHourArguments = generateArgumentMap(lastMachine);
						Map<String, double[]> currentHourArguments = generateArgumentMap(currentMachine);

						double[] values = extract(lastHourArguments.get(metric), currentHourArguments.get(metric), maxMinute,
						      minute);

						processMeitrc(domain, ip, metric, conditions, maxMinute, values);
					}
				}
			}
		}
	}

	private void processMeitrc(String domain, String ip, String metric, List<Condition> conditions, int maxMinute,
	      double[] values) {
		try {
			double[] baseline = new double[maxMinute];
			List<AlertResultEntity> alerts = m_dataChecker.checkData(values, baseline, conditions);

			for (AlertResultEntity alertResult : alerts) {
				AlertEntity entity = new AlertEntity();

				entity.setDate(alertResult.getAlertTime()).setContent(alertResult.getContent())
				      .setLevel(alertResult.getAlertLevel());
				entity.setMetric(metric).setType(getName()).setGroup(domain);
				entity.getParas().put("ip", ip);
				m_sendManager.addAlert(entity);
			}
		} catch (Exception e) {
			Cat.logError(e);
		}
	}

	private Set<String> queryDomains() {
		Set<String> domains = new HashSet<String>();
		ModelRequest request = new ModelRequest("cat", System.currentTimeMillis());

		if (m_transactionService.isEligable(request)) {
			ModelResponse<TransactionReport> response = m_transactionService.invoke(request);
			domains.addAll(response.getModel().getDomainNames());
		}
		return domains;
	}

	@Override
	public void run() {
		boolean active = true;
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			active = false;
		}

		while (active) {
			Transaction t = Cat.newTransaction("AlertHeartbeat", TimeHelper.getMinuteStr());
			long current = System.currentTimeMillis();

			try {
				Set<String> domains = queryDomains();

				for (String domain : domains) {
					if (m_configManager.validateDomain(domain) && StringUtils.isNotEmpty(domain)) {
						try {
							processDomain(domain);
						} catch (Exception e) {
							Cat.logError(e);
						}
					}
				}

				t.setStatus(Transaction.SUCCESS);
			} catch (Exception e) {
				t.setStatus(e);
			} finally {
				t.complete();
			}
			long duration = System.currentTimeMillis() - current;

			try {
				if (duration < DURATION) {
					Thread.sleep(DURATION - duration);
				}
			} catch (InterruptedException e) {
				active = false;
			}
		}
	}

	@Override
	public void shutdown() {
	}

}
