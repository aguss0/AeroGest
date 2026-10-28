package ar.edu.utn.frc.aerogest.flight;

import org.springframework.boot.SpringApplication;

public class TestFlightServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(FlightServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
