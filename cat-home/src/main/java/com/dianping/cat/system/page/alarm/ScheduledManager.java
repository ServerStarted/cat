package com.dianping.cat.system.page.alarm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.unidal.dal.jdbc.DalException;
import org.unidal.dal.jdbc.DalNotFoundException;
import org.unidal.helper.Threads;
import org.unidal.helper.Threads.Task;
import org.unidal.lookup.annotation.Inject;

import com.dianping.cat.Cat;
import com.dianping.cat.configuration.ServerConfigManager;
import com.dianping.cat.core.dal.Project;
import com.dianping.cat.helper.TimeHelper;
import com.dianping.cat.home.dal.alarm.ScheduledReport;
import com.dianping.cat.home.dal.alarm.ScheduledReportDao;
import com.dianping.cat.home.dal.alarm.ScheduledReportEntity;
import com.dianping.cat.home.dal.alarm.ScheduledSubscription;
import com.dianping.cat.home.dal.alarm.ScheduledSubscriptionDao;
import com.dianping.cat.home.dal.alarm.ScheduledSubscriptionEntity;
import com.dianping.cat.service.ProjectService;
import com.dianping.cat.system.page.alarm.UserReportSubState.UserReportSubStateCompartor;
import org.unidal.lookup.util.StringUtils;

public class ScheduledManager implements Initializable {

	@Inject
	private ScheduledReportDao m_scheduledReportDao;

	@Inject
	private ScheduledSubscriptionDao m_scheduledReportSubscriptionDao;

	@Inject
	private ProjectService m_projectService;

	@Inject
	private ServerConfigManager m_serverConfigManager;

	private Map<String, ScheduledReport> m_reports = new HashMap<String, ScheduledReport>();

	public List<String> queryEmailsBySchReportId(int scheduledReportId) throws DalException {
		List<String> emails = new ArrayList<String>();
		List<ScheduledSubscription> subscriptions = m_scheduledReportSubscriptionDao.findByScheduledReportId(
		      scheduledReportId, ScheduledSubscriptionEntity.READSET_FULL);

		for (ScheduledSubscription subscription : subscriptions) {
			emails.add(subscription.getUserName() + "@dianping.com");
		}
		return emails;
	}

	public Collection<ScheduledReport> queryScheduledReports() {
		return m_reports.values();
	}

	public void queryScheduledReports(Model model, String userName) {
		List<UserReportSubState> userRules = new ArrayList<UserReportSubState>();
		try {
			Collection<ScheduledReport> reports = m_reports.values();

			for (ScheduledReport report : reports) {
				if (m_serverConfigManager.validateDomain(report.getDomain())) {
					int scheduledReportId = report.getId();
					UserReportSubState userSubState = new UserReportSubState(report);

					userRules.add(userSubState);
					try {
						m_scheduledReportSubscriptionDao.findByPK(scheduledReportId, userName,
						      ScheduledSubscriptionEntity.READSET_FULL);
						userSubState.setSubscriberState(1);
					} catch (DalNotFoundException nfe) {
					} catch (DalException e) {
						Cat.logError(e);
					}
				}
			}
		} catch (Exception e) {
			Cat.logError(e);
		}
		Collections.sort(userRules, new UserReportSubStateCompartor());
		model.setUserReportSubStates(userRules);
	}

	public void scheduledReportDelete(Payload payload) {
		int id = payload.getScheduledReportId();
		ScheduledReport proto = m_scheduledReportDao.createLocal();

		proto.setKeyId(id);

		try {
			ScheduledReport report = m_scheduledReportDao.findByPK(id, ScheduledReportEntity.READSET_FULL);
			String domain = report.getDomain();
			m_scheduledReportDao.deleteByPK(proto);

			if (StringUtils.isNotEmpty(domain)) {
				m_reports.remove(domain);
			}
		} catch (Exception e) {
			Cat.logError(e);
		}
	}

	public boolean scheduledReportSub(Payload payload, String userName) {
		int subState = payload.getUserSubState();
		int scheduledReportId = payload.getScheduledReportId();

		ScheduledSubscription scheduledReportSubscription = m_scheduledReportSubscriptionDao.createLocal();

		scheduledReportSubscription.setKeyScheduledReportId(scheduledReportId);
		scheduledReportSubscription.setKeyUserName(userName);
		scheduledReportSubscription.setUserName(userName);
		scheduledReportSubscription.setScheduledReportId(scheduledReportId);

		try {
			if (subState == 1) {
				m_scheduledReportSubscriptionDao.deleteByPK(scheduledReportSubscription);
			} else {
				m_scheduledReportSubscriptionDao.insert(scheduledReportSubscription);
			}
		} catch (DalException e) {
			Cat.logError(e);
			return false;
		}
		return true;
	}

	public void scheduledReportUpdate(Payload payload, Model model) {
		int id = payload.getScheduledReportId();

		try {
			ScheduledReport scheduledReport = m_scheduledReportDao.findByPK(id, ScheduledReportEntity.READSET_FULL);

			model.setScheduledReport(scheduledReport);
		} catch (DalException e) {
			Cat.logError(e);
		}
	}

	public void scheduledReportUpdateSubmit(Payload payload, Model model) {
		int id = payload.getScheduledReportId();
		String content = payload.getContent();
		ScheduledReport entity = m_scheduledReportDao.createLocal();

		entity.setNames(content);
		entity.setKeyId(id);
		try {
			m_scheduledReportDao.updateByPK(entity, ScheduledReportEntity.UPDATESET_UPDATE_REPORTS);
			model.setOpState(Handler.SUCCESS);
		} catch (Exception e) {
			model.setOpState(Handler.FAIL);
		}
	}

	@Override
	public void initialize() throws InitializationException {
		if (m_serverConfigManager.isAlertMachine() && !m_serverConfigManager.isLocalMode()) {
			Threads.forGroup("cat").start(new ScheduledReportUpdateTask());
		}
	}

	private void loadData() {
		List<ScheduledReport> reports = new ArrayList<ScheduledReport>();
		try {
			reports = m_scheduledReportDao.findAll(ScheduledReportEntity.READSET_FULL);
		} catch (DalException e1) {
			Cat.logError(e1);
		}

		for (ScheduledReport report : reports) {
			try {
				String domain = report.getDomain();
				Project project = m_projectService.findByDomain(domain);

				if (project == null) {
					project = m_projectService.findByCmdbDomain(domain);
				}

				if (project != null) {
					String cmdbDomain = project.getCmdbDomain();

					if (StringUtils.isNotEmpty(cmdbDomain) && !report.getDomain().equals(cmdbDomain)
					      && !m_reports.containsKey(cmdbDomain)) {
						ScheduledReport entity = m_scheduledReportDao.createLocal();

						entity.setKeyId(report.getKeyId());
						entity.setId(report.getId());
						entity.setNames(report.getNames());
						entity.setDomain(cmdbDomain);

						int succ = 0;
						try {
							succ = m_scheduledReportDao.updateByPK(entity, ScheduledReportEntity.UPDATESET_FULL);
						} catch (DalException e) {
						}
						if (succ > 0) {
							report.setDomain(cmdbDomain);
							m_reports.put(cmdbDomain, report);
						} else {
							m_reports.put(domain, report);
						}

					} else {
						m_reports.put(domain, report);
					}
				} else {
					m_reports.put(domain, report);
				}
			} catch (Exception e) {
				Cat.logError(e);
			}
		}
	}

	private void updateData(String domain) {
		try {
			m_scheduledReportDao.findByDomain(domain, ScheduledReportEntity.READSET_FULL);
		} catch (DalNotFoundException e) {
			ScheduledReport entity = m_scheduledReportDao.createLocal();
			entity.setNames("transaction;event;problem;health");
			entity.setDomain(domain);

			try {
				m_scheduledReportDao.insert(entity);

				ScheduledReport r = m_scheduledReportDao.findByDomain(domain, ScheduledReportEntity.READSET_FULL);
				m_reports.put(domain, r);
			} catch (DalNotFoundException e1) {
			} catch (Exception e2) {
				Cat.logError(e2);
			}

		} catch (Exception e1) {
			Cat.logError(e1);
		}

	}

	public void refreshScheduledReport() {
		Map<String, Project> projects = m_projectService.findAllProjects();

		for (Entry<String, Project> entry : projects.entrySet()) {
			String domain = entry.getKey();
			String cmdbDomain = entry.getValue().getCmdbDomain();

			if (StringUtils.isNotEmpty(cmdbDomain)) {
				updateData(cmdbDomain);
			} else if (StringUtils.isEmpty(cmdbDomain)) {
				updateData(domain);
			}
		}
	}

	public class ScheduledReportUpdateTask implements Task {

		@Override
		public String getName() {
			return "ScheduledReport-Domain-Update";
		}

		@Override
		public void run() {
			loadData();

			boolean active = true;
			while (active) {
				try {
					refreshScheduledReport();
				} catch (Exception e) {
					Cat.logError(e);
				}
				try {
					Thread.sleep(TimeHelper.ONE_DAY);
				} catch (InterruptedException e) {
					active = false;
				}
			}
		}

		@Override
		public void shutdown() {
		}
	}
}
