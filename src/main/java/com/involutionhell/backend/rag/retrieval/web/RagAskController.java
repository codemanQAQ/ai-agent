package com.involutionhell.backend.rag.retrieval.web;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.rag.retrieval.api.RagAnswerResponse;
import com.involutionhell.backend.rag.retrieval.api.RagAskFacade;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/public/rag", produces = MediaType.APPLICATION_JSON_VALUE)
public class RagAskController {

    private final RagAskFacade ragAskFacade;

    public RagAskController(RagAskFacade ragAskFacade) {
        this.ragAskFacade = ragAskFacade;
    }

    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RagAnswerResponse> ask(@Valid @RequestBody RagAskRequest request) {
        return ApiResponse.ok(ragAskFacade.ask(request));
    }
}
