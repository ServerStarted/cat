package com.dianping.cat.broker.api.page.batch;

import java.io.IOException;
import java.net.URLDecoder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.util.StringUtils;
import org.unidal.tuple.Pair;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;

import com.dianping.cat.Cat;
import com.dianping.cat.broker.api.app.AppConsumer;
import com.dianping.cat.broker.api.app.proto.AppDataProto;
import com.dianping.cat.broker.api.app.proto.ProtoData;
import com.dianping.cat.broker.api.page.RequestUtils;
import com.dianping.cat.config.app.AppConfigManager;
import com.dianping.cat.message.Event;
import com.dianping.cat.service.IpService;
import com.dianping.cat.service.IpService.IpInfo;

public class Handler implements PageHandler<Context>, LogEnabled {

	@Inject
	private AppConsumer m_appDataConsumer;

	@Inject
	private IpService m_ipService;

	@Inject
	private AppConfigManager m_appConfigManager;

	@Inject
	private RequestUtils m_util;

	private Logger m_logger;

	private volatile int m_error;

	public static final String TOO_LONG = "toolongurl.bin";

	private static final String VERSION_TWO = "2";

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "batch")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "batch")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Payload payload = ctx.getPayload();
		HttpServletRequest request = ctx.getHttpServletRequest();
		HttpServletResponse response = ctx.getHttpServletResponse();
		String userIp = m_util.getRemoteIp(request);
		String version = payload.getVersion();
		boolean success = true;

		if (userIp != null) {
			success = processVersions(payload, request, userIp, version);
		} else {
			success = false;
			Cat.logEvent("UnknownIp", "Batch", Event.SUCCESS, null);
			m_logger.info("unknown http request, x-forwarded-for:" + request.getHeader("x-forwarded-for"));
		}

		if (success) {
			response.getWriter().write("OK");
		} else {
			response.getWriter().write("validate request!");
		}
	}

	private boolean processVersions(Payload payload, HttpServletRequest request, String userIp, String version) {
		boolean success = false;

		if (VERSION_TWO.equals(version)) {
			Pair<Integer, Integer> infoPair = queryNetworkInfo(request, userIp);

			if (infoPair != null) {
				int cityId = infoPair.getKey();
				int operatorId = infoPair.getValue();
				String content = payload.getContent();

				processVersion2Content(cityId, operatorId, content, version);
				success = true;
			} else {
				Cat.logEvent("InvalidIpInfo", "batch:" + userIp, Event.SUCCESS, userIp);
			}
		} else {
			Cat.logEvent("InvalidVersion", "batch:" + version, Event.SUCCESS, version);
		}
		return success;
	}

	private void offerQueue(ProtoData appData) {
		boolean success = m_appDataConsumer.enqueue(appData);

		if (!success) {
			m_error++;

			if (m_error % 1000 == 0) {
				Cat.logEvent("Discard", "Batch", Event.SUCCESS, null);
				m_logger.error("Error when offer appData to queue , discard number " + m_error);
			}
		}
	}

	private void processVersion2Record(int cityId, int operatorId, String record) {
		String[] items = record.split("\t");

		if (items.length == 10) {
			AppDataProto appData = new AppDataProto();

			try {
				String url = URLDecoder.decode(items[4], "utf-8").toLowerCase();
				int index = url.indexOf("?");

				if (index > 0) {
					url = url.substring(0, index);
				}
				Integer command = m_appConfigManager.getCommands().get(url);

				if (command != null) {
					// appData.setTimestamp(Long.parseLong(items[0]));
					appData.setTimestamp(System.currentTimeMillis());
					appData.setCommand(command);
					appData.setNetwork(Integer.parseInt(items[1]));
					appData.setVersion(Integer.parseInt(items[2]));
					appData.setConnectType(Integer.parseInt(items[3]));
					appData.setCode(Integer.parseInt(items[5]));
					appData.setPlatform(Integer.parseInt(items[6]));
					appData.setRequestByte(Integer.parseInt(items[7]));
					appData.setResponseByte(Integer.parseInt(items[8]));
					appData.setResponseTime(Integer.parseInt(items[9]));
					appData.setCity(cityId);
					appData.setOperator(operatorId);
					appData.setCount(1);

					int responseTime = appData.getResponseTime();

					if (responseTime < 60 * 1000 && responseTime >= 0) {
						offerQueue(appData);

						Cat.logEvent("Batch.Command", url, Event.SUCCESS, null);
					} else if (responseTime > 0) {
						Integer tooLong = m_appConfigManager.getCommands().get(TOO_LONG);

						if (tooLong != null) {
							appData.setCommand(tooLong);
							offerQueue(appData);
						}
						Cat.logEvent("Batch.ResponseTooLong", url, Event.SUCCESS, String.valueOf(responseTime));
					} else {
						Cat.logEvent("Batch.ResponseTimeError", url, Event.SUCCESS, String.valueOf(responseTime));
					}
				} else {
					Cat.logEvent("Batch.UnknownCommand", url, Event.SUCCESS, items[4]);
				}
			} catch (Exception e) {
				Cat.logError(e);
				m_logger.error(e.getMessage(), e);
			}
		} else {
			Cat.logEvent("Batch.InvalidRecord", record, Event.SUCCESS, null);
		}
	}

	private Pair<Integer, Integer> queryNetworkInfo(HttpServletRequest request, String userIp) {
		IpInfo ipInfo = m_ipService.findIpInfoByString(userIp);

		if (ipInfo != null) {
			String province = ipInfo.getProvince();
			String operatorStr = ipInfo.getChannel();
			Integer cityId = m_appConfigManager.getCities().get(province);
			Integer operatorId = m_appConfigManager.getOperators().get(operatorStr);

			if (cityId != null && operatorId != null) {
				return new Pair<Integer, Integer>(cityId, operatorId);
			} else {
				Cat.logEvent("UnknownCityOperator", "batch:" + province + ":" + operatorStr, Event.SUCCESS, null);
			}
		}
		return null;
	}

	private void processVersion2Content(Integer cityId, Integer operatorId, String content, String version) {
		String[] records = content.split("\n");

		for (String record : records) {
			try {
				if (StringUtils.isNotEmpty(record)) {
					processVersion2Record(cityId, operatorId, record);
				}
			} catch (Exception e) {
				Cat.logError(e);
			}
		}
	}

}