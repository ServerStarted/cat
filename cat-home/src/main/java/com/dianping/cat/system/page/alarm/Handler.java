package com.dianping.cat.system.page.alarm;

import java.io.IOException;

import javax.servlet.ServletException;

import org.unidal.lookup.annotation.Inject;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;
import org.unidal.web.mvc.annotation.PreInboundActionMeta;

import com.dianping.cat.system.SystemPage;
import com.dianping.cat.system.page.login.service.LoginMember;

public class Handler implements PageHandler<Context> {
	public static final String FAIL = "Fail";

	public static final String SUCCESS = "Success";

	@Inject
	private JspViewer m_jspViewer;

	@Inject
	private RecordManager m_recordManager;

	@Inject
	private ScheduledManager m_scheduledManager;

	private String getLoginUserName(Context ctx) {
		LoginMember member = ctx.getSigninMember();

		if (member != null) {
			return member.getUserName();
		}

		return null;
	}

	@Override
	@PreInboundActionMeta("login")
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "alarm")
	public void handleInbound(Context ctx) throws ServletException, IOException {
	}

	@Override
	@OutboundActionMeta(name = "alarm")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();
		Action action = payload.getAction();
		String userName = getLoginUserName(ctx);
		boolean result = false;

		switch (action) {
		case SCHEDULED_REPORT_DELETE:
			m_scheduledManager.scheduledReportDelete(payload);
			m_scheduledManager.queryScheduledReports(model, userName);
			break;
		case SCHEDULED_REPORT_LIST:
			m_scheduledManager.queryScheduledReports(model, userName);
			break;
		case SCHEDULED_REPORT_UPDATE:
			m_scheduledManager.scheduledReportUpdate(payload, model);
			break;
		case SCHEDULED_REPORT_UPDATE_SUBMIT:
			m_scheduledManager.scheduledReportUpdateSubmit(payload, model);
			break;
		case SCHEDULED_REPORT_SUB:
			result = m_scheduledManager.scheduledReportSub(payload, userName);
			if (result) {
				model.setOpState(SUCCESS);
			} else {
				model.setOpState(FAIL);
			}
			break;
		case REPORT_RECORD_LIST:
			m_recordManager.queryUserReportRecords(model, userName);
			break;
		case ALARM_RECORD_DETAIL:
			m_recordManager.queryAlarmRecordDetail(payload, model);
			break;
		}

		model.setAction(payload.getAction());
		model.setPage(SystemPage.ALARM);
		m_jspViewer.view(ctx, model);
	}

}
