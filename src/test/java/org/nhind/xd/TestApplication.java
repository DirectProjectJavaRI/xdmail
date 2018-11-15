package org.nhind.xd;

import org.nhindirect.gateway.springconfig.ConfigServiceClientConfig;
import org.nhindirect.gateway.springconfig.DNSResolverConfig;
import org.nhindirect.gateway.springconfig.DSNGeneratorConfig;
import org.nhindirect.gateway.springconfig.RouteResolverConfig;
import org.nhindirect.gateway.streams.SmtpGatewayMessageSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ComponentScan({"org.nhindirect.config", "org.nhind.config", "org.nhind.xd"})
@Import({ConfigServiceClientConfig.class, DSNGeneratorConfig.class, DNSResolverConfig.class, RouteResolverConfig.class, SmtpGatewayMessageSource.class})
public class TestApplication
{
    public static void main(String[] args) 
    {
        SpringApplication.run(TestApplication.class, args);
    }  
}
