package org.nhind.xd;

import org.nhindirect.gateway.springconfig.ConfigServiceClientConfig;
import org.nhindirect.gateway.springconfig.DNSResolverConfig;
import org.nhindirect.gateway.springconfig.DSNGeneratorConfig;
import org.nhindirect.gateway.springconfig.RouteResolverConfig;
import org.nhindirect.gateway.streams.SmtpGatewayMessageSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(exclude = {R2dbcAutoConfiguration.class})
@Import({ConfigServiceClientConfig.class, DSNGeneratorConfig.class, DNSResolverConfig.class, RouteResolverConfig.class, SmtpGatewayMessageSource.class})
public class TestApplication
{
    public static void main(String[] args) 
    {
        SpringApplication.run(TestApplication.class, args);
    } 
}
