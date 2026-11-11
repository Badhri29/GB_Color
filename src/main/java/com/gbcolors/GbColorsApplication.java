package com.gbcolors;

import com.gbcolors.config.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GbColorsApplication {

    public static void main(String[] args) {
        EnvLoader.loadAndConfigureDatasource();
        SpringApplication.run(GbColorsApplication.class, args);
    }
}
