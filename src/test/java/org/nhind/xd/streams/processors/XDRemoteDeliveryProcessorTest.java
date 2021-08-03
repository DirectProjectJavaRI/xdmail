package org.nhind.xd.streams.processors;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
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
