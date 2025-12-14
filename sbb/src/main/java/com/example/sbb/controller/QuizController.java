package com.example.sbb.controller;

import com.example.sbb.domain.Folder;
import com.example.sbb.domain.quiz.QuestionDiscussion;
import com.example.sbb.domain.quiz.QuizQuestion;
import com.example.sbb.domain.user.SiteUser;
import com.example.sbb.domain.user.UserService;
import com.example.sbb.service.FriendService;
import com.example.sbb.service.DiscussionService;
import com.example.sbb.repository.QuizQuestionRepository;
import com.example.sbb.repository.FolderRepository;
import com.example.sbb.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import java.security.Principal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/quiz")
@RequiredArgsConstructor
public class QuizController {

    private final QuizQuestionRepository quizQuestionRepository;
    private final UserService userService;
    private final FolderRepository folderRepository;
    private final GroupService groupService;
    private final FriendService friendService;
    private final DiscussionService discussionService;

    // 전체 퀴즈 목록(간단히)
  @GetMapping("/list")
  public String list(Model model,
                   Principal principal,
                   @RequestParam(value = "page", defaultValue = "0") int page,
                   @RequestParam(value = "folderId", required = false) Long folderId) {
    if (principal == null) return "redirect:/login";

    // TODO: JPA 로직 전부 잠시 주석 처리
    
    SiteUser user = userService.getUser(principal.getName());
    List<Folder> folders = folderRepository.findByUserOrderByCreatedAtAsc(user)
            .stream()
            .filter(f -> {
                String n = f.getName();
                return n != null && !n.equals("맞은 문제") && !n.equals("틀린 문제");
            })
            .toList();

    Folder folder = null;
    if (folderId != null) {
        folder = folderRepository.findByIdAndUser(folderId, user).orElse(null);
    }
    if (folder == null && !folders.isEmpty()) {
        folder = folders.get(0);
    }

    int pageIndex = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pageIndex, 10, Sort.by("createdAt").ascending());
        Page<QuizQuestion> pageData = (folder != null)
                ? quizQuestionRepository.findByUserAndFolderOrderByCreatedAtAsc(user, folder, pageable)
                : quizQuestionRepository.findByUserOrderByCreatedAtAsc(user, pageable);

    int totalPages = pageData.getTotalPages() == 0 ? 1 : pageData.getTotalPages();

    model.addAttribute("questions", pageData.getContent());
    model.addAttribute("currentPage", pageData.getNumber());
    model.addAttribute("totalPages", totalPages);
    model.addAttribute("hasPrevious", pageData.hasPrevious());
    model.addAttribute("hasNext", pageData.hasNext());
    model.addAttribute("totalElements", pageData.getTotalElements());
    model.addAttribute("folders", folders);
    model.addAttribute("selectedFolder", folder);
    model.addAttribute("friends", friendService.myFriends(user));
    if (!pageData.isEmpty()) {
        QuizQuestion first = pageData.getContent().get(0);
        model.addAttribute("firstChoiceList", extractChoices(first.getChoices()));
    }
    String folderQuery = (folder != null) ? "?folderId=" + folder.getId() : "";
    model.addAttribute("folderQuery", folderQuery);
    model.addAttribute("folderQueryAmp", folder != null ? "&folderId=" + folder.getId() : "");
    model.addAttribute("memberships", groupService.memberships(user));
    

    return "quiz_list";
}


    @GetMapping("/next")
    public String goToNext(Principal principal,
                           @RequestParam(value = "folderId", required = false) Long folderId,
                           RedirectAttributes rttr) {
        if (principal == null) return "redirect:/login";

        SiteUser user = userService.getUser(principal.getName());
        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findByIdAndUser(folderId, user).orElse(null);
        }
        QuizQuestion next = findNextPendingQuestion(user, folder);

        if (next == null) {
            rttr.addFlashAttribute("message", "풀 문제를 모두 완료했습니다.");
            String suffix = (folder != null) ? "?folderId=" + folder.getId() : "";
            return "redirect:/quiz/list" + suffix;
        }
        String suffix = (folder != null) ? "?folderId=" + folder.getId() : "";
        return "redirect:/quiz/solve/" + next.getId() + suffix;
    }

    // 문제 풀기 화면
    @GetMapping("/solve/{id}")
    public String showQuiz(@PathVariable Long id,
                           @RequestParam(value = "folderId", required = false) Long folderId,
                           @RequestParam(value = "groupId", required = false) Long groupId,
                           @RequestParam(value = "discussionQuestionId", required = false) Long discussionQuestionId,
                           Principal principal,
                           Model model,
                           RedirectAttributes rttr) {
        if (principal == null) return "redirect:/login";

        SiteUser user = userService.getUser(principal.getName());
        QuizQuestion q = quizQuestionRepository.findById(id).orElse(null);

        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findByIdAndUser(folderId, user).orElse(null);
        }
        if (folder == null) {
            folder = (q != null) ? q.getFolder() : null;
        }

        if (q == null || !q.getUser().getId().equals(user.getId())) {
            String suffix = buildFolderSuffix(folder);
            return "redirect:/quiz/list" + suffix;
        }

        model.addAttribute("question", q);
        model.addAttribute("selectedFolder", folder);
        model.addAttribute("folders", folderRepository.findByUserOrderByCreatedAtAsc(user));
        model.addAttribute("choiceList", extractChoices(q.getChoices()));
        model.addAttribute("questionList", loadQuestionList(user, folder));
        model.addAttribute("discussionGroupId", groupId);
        model.addAttribute("discussionQuestionId", discussionQuestionId);
        return "quiz_solve";
    }

    @GetMapping("/discussion/{id}")
    public String discussion(@PathVariable Long id,
                             Principal principal,
                             Model model,
                             RedirectAttributes rttr) {
        if (principal == null) return "redirect:/login";
        SiteUser user = userService.getUser(principal.getName());
        QuizQuestion q = quizQuestionRepository.findById(id).orElse(null);
        if (q == null || !q.getUser().getId().equals(user.getId())) {
            rttr.addFlashAttribute("error", "문제를 찾을 수 없습니다.");
            return "redirect:/quiz/list";
        }
        model.addAttribute("question", q);
        model.addAttribute("comments", discussionService.list(q));
        return "quiz_discussion";
    }

    @PostMapping("/discussion/{id}")
    public String addDiscussion(@PathVariable Long id,
                                @RequestParam("content") String content,
                                Principal principal,
                                RedirectAttributes rttr) {
        if (principal == null) return "redirect:/login";
        SiteUser user = userService.getUser(principal.getName());
        QuizQuestion q = quizQuestionRepository.findById(id).orElse(null);
        if (q == null || !q.getUser().getId().equals(user.getId())) {
            rttr.addFlashAttribute("error", "문제를 찾을 수 없습니다.");
            return "redirect:/quiz/list";
        }
        discussionService.addComment(q, user, content);
        return "redirect:/quiz/discussion/" + id;
    }

    @GetMapping("/discussion/{id}/list")
    @ResponseBody
    public ResponseEntity<?> listDiscussionInline(@PathVariable Long id,
                                                  Principal principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        SiteUser user = userService.getUser(principal.getName());
        QuizQuestion q = discussionService.getOwnedQuestion(user, id);
        if (q == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).body("no question");
        List<QuestionDiscussion> comments = discussionService.list(q);
        List<DiscussionDto> dto = comments.stream()
                .map(c -> new DiscussionDto(
                        c.getId(),
                        c.getUser().getUsername(),
                        c.getContent(),
                        c.getCreatedAt() != null ? c.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")) : ""
                ))
                .toList();
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/discussion/{id}/add")
    @ResponseBody
    public ResponseEntity<?> addDiscussionInline(@PathVariable Long id,
                                                 @RequestParam("content") String content,
                                                 Principal principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        SiteUser user = userService.getUser(principal.getName());
        QuizQuestion q = discussionService.getOwnedQuestion(user, id);
        if (q == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).body("no question");
        QuestionDiscussion saved = discussionService.addComment(q, user, content);
        if (saved == null) return ResponseEntity.badRequest().body("invalid");
        DiscussionDto dto = new DiscussionDto(
                saved.getId(),
                saved.getUser().getUsername(),
                saved.getContent(),
                saved.getCreatedAt() != null ? saved.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")) : ""
        );
        return ResponseEntity.ok(dto);
    }

    // 답 제출
    @PostMapping("/solve/{id}")
    public String submitQuiz(@PathVariable Long id,
                             @RequestParam("answer") String userAnswer,
                             @RequestParam(value = "folderId", required = false) Long folderId,
                             Principal principal,
                             Model model,
                             RedirectAttributes rttr) {
        if (principal == null) return "redirect:/login";

        SiteUser user = userService.getUser(principal.getName());
        QuizQuestion q = quizQuestionRepository.findById(id).orElse(null);

        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findByIdAndUser(folderId, user).orElse(null);
        }
        if (folder == null) {
            folder = (q != null) ? q.getFolder() : null;
        }

        if (q == null || !q.getUser().getId().equals(user.getId())) {
            rttr.addFlashAttribute("message", "문제를 찾을 수 없습니다.");
            String suffix = buildFolderSuffix(folder);
            return "redirect:/quiz/list" + suffix;
        }

        String normalizedUser = normalizeAnswerText(userAnswer);
        String normalizedCorrect = normalizeAnswerText(q.getAnswer());

        boolean isCorrect = !normalizedCorrect.isEmpty()
                && normalizedCorrect.equals(normalizedUser);

        q.setSolved(true);
        q.setCorrect(isCorrect);
        quizQuestionRepository.save(q);
        userService.recordSolve(user, isCorrect);

        model.addAttribute("question", q);
        model.addAttribute("userAnswer", userAnswer);
        model.addAttribute("correct", isCorrect);
        model.addAttribute("choiceList", extractChoices(q.getChoices()));
        model.addAttribute("normalizedCorrectAnswer", normalizedCorrect);
        model.addAttribute("normalizedUserAnswer", normalizedUser);
        model.addAttribute("selectedFolder", folder);
        model.addAttribute("folders", folderRepository.findByUserOrderByCreatedAtAsc(user));
        model.addAttribute("questionList", loadQuestionList(user, folder));

        QuizQuestion next = findNextPendingQuestion(user, folder);
        if (next != null) {
            model.addAttribute("nextQuestionId", next.getId());
        }

        return "quiz_result";
    }

    @PostMapping("/inline-submit/{id}")
    @ResponseBody
    public ResponseEntity<?> inlineSubmit(@PathVariable Long id,
                                          @RequestParam("answer") String userAnswer,
                                          @RequestParam(value = "folderId", required = false) Long folderId,
                                          Principal principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        SiteUser user = userService.getUser(principal.getName());
        QuizQuestion q = quizQuestionRepository.findById(id).orElse(null);
        if (q == null || !q.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("no question");
        }

        Folder folder = null;
        if (folderId != null) {
            folder = folderRepository.findByIdAndUser(folderId, user).orElse(null);
        }
        if (folder == null) {
            folder = q.getFolder();
        }

        String normalizedUser = normalizeAnswerText(userAnswer);
        String normalizedCorrect = normalizeAnswerText(q.getAnswer());
        boolean isCorrect = !normalizedCorrect.isEmpty() && normalizedCorrect.equals(normalizedUser);

        q.setSolved(true);
        q.setCorrect(isCorrect);

        // 맞은 문제/틀린 문제 폴더로 자동 분류
        String targetFolderName = isCorrect ? "맞은 문제" : "틀린 문제";
        Folder targetFolder = folderRepository.findByUserAndName(user, targetFolderName)
                .orElseGet(() -> {
                    Folder f = new Folder();
                    f.setName(targetFolderName);
                    f.setUser(user);
                    return folderRepository.save(f);
                });
        q.setFolder(targetFolder);
        quizQuestionRepository.save(q);
        userService.recordSolve(user, isCorrect);

        return ResponseEntity.ok(
                java.util.Map.of(
                        "correct", isCorrect,
                        "normalizedCorrect", normalizedCorrect,
                        "normalizedUser", normalizedUser
                )
        );
    }

    private List<ChoiceOption> extractChoices(String rawChoices) {
        List<ChoiceOption> result = new ArrayList<>();
        if (rawChoices == null || rawChoices.isBlank()) {
            return result;
        }

        Pattern pattern = Pattern.compile("(\\d+)[\\).]\\s*(.*?)(?=(?:\\s*\\d+[\\).])|$)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(rawChoices);
        while (matcher.find()) {
            String prefix = matcher.group(1).trim();
            String text = matcher.group(2).trim();
            if (!text.isEmpty()) {
                result.add(new ChoiceOption(prefix, text));
            }
        }

        if (result.isEmpty()) {
            String[] chunks = rawChoices.split("[\\n\\r,;]+");
            for (String chunk : chunks) {
                String trimmed = chunk.trim();
                if (!trimmed.isEmpty()) {
                    result.add(new ChoiceOption(null, trimmed));
                }
            }
        }

        return result;
    }

    private QuizQuestion findNextPendingQuestion(SiteUser user, Folder folder) {
        List<QuizQuestion> targetList = (folder != null)
                ? quizQuestionRepository.findByUserAndFolderAndSolvedFalseOrderByCreatedAtAsc(user, folder)
                : quizQuestionRepository.findByUserAndSolvedFalseOrderByCreatedAtAsc(user);

        return targetList.stream()
                .findFirst()
                .orElse(null);
    }

    private List<QuizQuestion> loadQuestionList(SiteUser user, Folder folder) {
        return (folder != null)
                ? quizQuestionRepository.findByUserAndFolderOrderByCreatedAtAsc(user, folder)
                : quizQuestionRepository.findByUserOrderByCreatedAtAsc(user);
    }

    private String buildFolderSuffix(Folder folder) {
        return (folder != null && folder.getId() != null) ? "?folderId=" + folder.getId() : "";
    }

    private static final Pattern NORMALIZE_PATTERN = Pattern.compile("[^A-Za-z0-9가-힣]");

    private static String normalizeAnswerText(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "";
        return NORMALIZE_PATTERN.matcher(trimmed).replaceAll("").toLowerCase();
    }

    public static class ChoiceOption {
        private final String number;
        private final String text;
        private final String value;
        private final String normalizedValue;

        public ChoiceOption(String number, String text) {
            this.number = number;
            this.text = text;
            this.value = (number != null && !number.isBlank())
                    ? number.trim()
                    : text;
            this.normalizedValue = normalizeAnswerText(this.value);
        }

        public String getNumber() {
            return number;
        }

        public String getText() {
            return text;
        }

        public String getValue() {
            return value;
        }

        public String getNormalizedValue() {
            return normalizedValue;
        }
    }

    public record DiscussionDto(Long id, String username, String content, String createdAt) {}
}
