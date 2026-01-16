package org.nhind.xd;

import static org.mockito.Mockito.mock;

import org.nhindirect.gateway.smtp.SmtpAgent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@SpringBootApplication()
public class TestApplication
{
    public static void main(String[] args) 
    {
        SpringApplication.run(TestApplication.class, args);
    } 
    
    @ConditionalOnMissingBean
    @Bean
    SmtpAgent mockSmtpAgent() {
    	
    	return mock(SmtpAgent.class);
    }
}
