package org.nhind.xd.streams.processors;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.nhind.xd.SpringBaseTest;
import org.springframework.beans.factory.annotation.Autowired;

public class XDRemoteDeliveryProcessorTest extends SpringBaseTest
{
	@Autowired
	protected XDRemoteDeliveryProcessor processor;
	
	@Test
	public void testInitProcessor()
	{
		assertNotNull(processor);
	}
}
