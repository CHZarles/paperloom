package com.yizhaoqi.smartpai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "jwt.secret-key=MDEyMzQ1Njc4OWFiY2RlZg==",
        "model-provider.security.secret-key=MDEyMzQ1Njc4OWFiY2RlZg==",
        "spring.datasource.url=jdbc:h2:mem:smartpai_context;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "paper.bootstrap.enabled=false",
        "elasticsearch.init.enabled=false"
})
class SmartPaiApplicationTests {

    @Test
    void contextLoads() {
    }

}
