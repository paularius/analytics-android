package io.segment.android.providers.mixpanel.test;

import io.segment.android.Analytics;
import io.segment.android.Constants;
import io.segment.android.models.Context;
import io.segment.android.models.EasyJSONObject;
import io.segment.android.models.EventProperties;
import io.segment.android.models.Identify;
import io.segment.android.models.Track;
import io.segment.android.models.Traits;
import io.segment.android.provider.BaseProviderInitializationActivity;
import io.segment.android.provider.Provider;
import io.segment.android.providers.MixpanelProvider;

import java.util.Calendar;
import java.util.Random;

import org.junit.Test;

import android.util.Log;

public class MixpanelAliasTest extends BaseProviderInitializationActivity {

	@Override
	public Provider getProvider() {
		return new MixpanelProvider();
	}

	@Override
	public EasyJSONObject getSettings() {
		EasyJSONObject settings = new EasyJSONObject();
		settings.put("token", "89f86c4aa2ce5b74cb47eb5ec95ad1f9");
		settings.put("people", true);
		return settings;
	}
	
	@Test
	public void testAlias() {
		
		reachReadyState();
		
		int random = (new Random()).nextInt();
		
		String sessionId = "android_session_id_" + random;
		String userId = "android_user_id_" + random;
		
		EventProperties properties = new EventProperties("revenue", 10.00);
		Traits traits = new Traits("$first_name", "Ilya " + random);
		Calendar timestamp = Calendar.getInstance();
		Context context = new Context();
		
		Analytics.setSessionId(sessionId);
		
		Log.e(Constants.TAG, "Mixpanel alias test is using session_id: " + 
				sessionId + ", and user_id: " + userId);
		
		provider.track(new Track(sessionId, "Anonymous Event", 
				properties, timestamp, context));
		
		provider.identify(new Identify(userId, traits, timestamp, context));
		
		provider.track(new Track(userId, "Identified Event", properties, timestamp, context));
		
		provider.flush();
		
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
}
