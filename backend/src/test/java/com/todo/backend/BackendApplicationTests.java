package com.todo.backend;

import com.todo.backend.controller.BookTitleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application-integration-tests.properties")
@AutoConfigureMockMvc
class BackendApplicationTests {

    @Autowired
    private BookTitleController bookTitleController;

	@Test
	void contextLoads() {
        assertThat(bookTitleController).isNotNull();
	}

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;


    @Test
    public void tryGettingBookTitles() throws Exception {
        ResponseEntity<String> response = restTemplate.
                getForEntity("/api/bookTitle", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
