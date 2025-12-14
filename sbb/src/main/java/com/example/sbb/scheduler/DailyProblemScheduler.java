package com.example.sbb.scheduler;

import com.example.sbb.domain.document.DocumentFile;
import com.example.sbb.repository.DocumentFileRepository;
import com.example.sbb.service.GeminiQuestionService;
import com.example.sbb.service.ProblemService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DailyProblemScheduler {

    private final DocumentFileRepository documentFileRepository;
    private final GeminiQuestionService geminiQuestionService;
    private final ProblemService problemService;

    // 매일 오전 9시
    // @Scheduled(cron = "0 0 9 * * *")
    public void generateDailyProblems() {
        // 스케줄러 호출 중단 (Gemini 쿼터 초과 방지)
        // List<DocumentFile> docs = documentFileRepository.findAll();
        // for (DocumentFile doc : docs) {
        //     String text = doc.getExtractedText();
        //     if (text == null || text.isBlank()) {
        //         continue;
        //     }
        //     String questions = geminiQuestionService.generateQuestionsFromText(text);
        //     problemService.saveProblem(doc, questions);
        // }
    }
}
