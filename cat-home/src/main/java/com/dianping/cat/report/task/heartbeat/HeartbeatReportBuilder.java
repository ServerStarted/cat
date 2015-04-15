package com.dianping.cat.report.task.heartbeat;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.unidal.dal.jdbc.DalException;
import org.unidal.lookup.annotation.Inject;

import com.dianping.cat.Cat;
import com.dianping.cat.configuration.NetworkInterfaceManager;
import com.dianping.cat.consumer.heartbeat.HeartbeatAnalyzer;
import com.dianping.cat.consumer.heartbeat.model.entity.HeartbeatReport;
import com.dianping.cat.consumer.heartbeat.model.transform.DefaultNativeBuilder;
import com.dianping.cat.consumer.transaction.TransactionAnalyzer;
import com.dianping.cat.core.dal.DailyReport;
import com.dianping.cat.core.dal.Graph;
import com.dianping.cat.core.dal.GraphDao;
import com.dianping.cat.helper.TimeHelper;
import com.dianping.cat.report.service.ReportServiceManager;
import com.dianping.cat.report.task.TaskHelper;
import com.dianping.cat.report.task.spi.ReportTaskBuilder;

public class HeartbeatReportBuilder implements ReportTaskBuilder {

	public static final String ID = HeartbeatAnalyzer.ID;

	@Inject
	protected GraphDao m_graphDao;

	@Inject
	protected ReportServiceManager m_reportService;

	@Inject
	private HeartbeatGraphCreator m_heartbeatGraphCreator;

	@Override
	public boolean buildDailyTask(String name, String domain, Date period) {
		try {
			Date end = TaskHelper.tomorrowZero(period);
			HeartbeatReport heartbeatReport = queryDailyHeartbeatReport(name, domain, period, end);
			DailyReport report = new DailyReport();

			report.setCreationDate(new Date());
			report.setDomain(domain);
			report.setContent("");
			report.setIp(NetworkInterfaceManager.INSTANCE.getLocalHostAddress());
			report.setName(name);
			report.setPeriod(period);
			report.setType(1);
			byte[] binaryContent = DefaultNativeBuilder.build(heartbeatReport);

			return m_reportService.insertDailyReport(report, binaryContent);
		} catch (Exception e) {
			Cat.logError(e);
			return false;
		}
	}

	@Override
	public boolean buildHourlyTask(String name, String domain, Date period) {
		try {
			List<Graph> graphs = qeueryHourlyGraphs(name, domain, period);
			if (graphs != null) {
				for (Graph graph : graphs) {
					if (graph.getSummaryContent() == null) {
						graph.setSummaryContent("");
					}
					m_graphDao.insert(graph);
				}
			}
		} catch (Exception e) {
			Cat.logError(e);
			return false;
		}
		return true;
	}

	@Override
	public boolean buildMonthlyTask(String name, String domain, Date period) {
		throw new UnsupportedOperationException("no month report builder for heartbeat!");
	}

	@Override
	public boolean buildWeeklyTask(String name, String domain, Date period) {
		throw new UnsupportedOperationException("no weekly report builder for heartbeat!");
	}

	private List<Graph> qeueryHourlyGraphs(String name, String domain, Date period) throws DalException {
		HeartbeatReport report = m_reportService.queryHeartbeatReport(domain, period, new Date(period.getTime()
		      + TimeHelper.ONE_HOUR));

		return m_heartbeatGraphCreator.splitReportToGraphs(report.getStartTime(), report.getDomain(),
		      HeartbeatAnalyzer.ID, report);
	}

	private HeartbeatReport queryDailyHeartbeatReport(String name, String domain, Date start, Date end) {
		Set<String> domains = m_reportService.queryAllDomainNames(start, end, TransactionAnalyzer.ID);
		HeartbeatDailyMerger merger = new HeartbeatDailyMerger(new HeartbeatReport(domain));
		long startTime = start.getTime();
		long endTime = end.getTime();

		for (; startTime < endTime; startTime += TimeHelper.ONE_HOUR) {
			HeartbeatReport report = m_reportService.queryHeartbeatReport(domain, new Date(startTime), new Date(startTime
			      + TimeHelper.ONE_HOUR));

			report.accept(merger);
		}

		HeartbeatReport heartbeatReport = merger.getHeartbeatReport();

		heartbeatReport.setStartTime(start);
		heartbeatReport.setEndTime(new Date(end.getTime() - 1));
		heartbeatReport.getDomainNames().addAll(domains);
		
		return heartbeatReport;
	}
}
