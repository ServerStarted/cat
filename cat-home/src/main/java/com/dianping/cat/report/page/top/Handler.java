package com.dianping.cat.report.page.top;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.servlet.ServletException;

import org.unidal.lookup.annotation.Inject;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;

import com.dianping.cat.Constants;
import com.dianping.cat.consumer.top.TopAnalyzer;
import com.dianping.cat.consumer.top.model.entity.TopReport;
import com.dianping.cat.helper.TimeHelper;
import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.page.PayloadNormalizer;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.dianping.cat.report.service.ReportServiceManager;
import com.dianping.cat.service.ModelRequest;
import com.dianping.cat.service.ModelResponse;
import com.dianping.cat.system.config.ExceptionConfigManager;

public class Handler implements PageHandler<Context> {
	@Inject
	private JspViewer m_jspViewer;

	@Inject
	private ReportServiceManager m_reportService;

	@Inject(type = ModelService.class, value = TopAnalyzer.ID)
	private ModelService<TopReport> m_service;

	@Inject
	private PayloadNormalizer m_normalizePayload;

	@Inject
	private ExceptionConfigManager m_configManager;

	private TopReport getReport(Payload payload) {
		String domain = Constants.CAT;
		ModelRequest request = new ModelRequest(domain, payload.getDate());

		if (m_service.isEligable(request)) {
			ModelResponse<TopReport> response = m_service.invoke(request);
			TopReport report = response.getModel();

			if (report == null || report.getDomains().size() == 0) {
				report = m_reportService.queryTopReport(domain, new Date(payload.getDate()), new Date(payload.getDate()
				      + TimeHelper.ONE_HOUR));
			}
			return report;
		} else {
			throw new RuntimeException("Internal error: no eligable top service registered for " + request + "!");
		}
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = TopAnalyzer.ID)
	public void handleInbound(Context ctx) throws ServletException, IOException {
	}

	@Override
	@OutboundActionMeta(name = TopAnalyzer.ID)
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();

		normalize(model, payload);

		TopReport report = getReport(payload);
		int minuteCount = payload.getMinuteCounts();

		if (!payload.getPeriod().isCurrent()) {
			minuteCount = 60;
		} else {
			minuteCount = payload.getMinuteCounts();
		}
		List<String> excludeDomains = Arrays.asList(Constants.FRONT_END);
		TopMetric displayTop = new TopMetric(minuteCount, payload.getTopCounts(), m_configManager, excludeDomains);

		displayTop.visitTopReport(report);
		model.setTopReport(report);
		model.setTopMetric(displayTop);
		m_jspViewer.view(ctx, model);
	}

	private void normalize(Model model, Payload payload) {
		model.setPage(ReportPage.TOP);
		model.setAction(Action.VIEW);
		m_normalizePayload.normalize(model, payload);
	}

}
