package com.example.sbb.service;

import com.example.sbb.domain.user.Friend;
import com.example.sbb.domain.user.SiteUser;
import com.example.sbb.domain.user.FriendRequest;
import com.example.sbb.domain.user.FriendShareRequest;
import com.example.sbb.repository.FriendRepository;
import com.example.sbb.repository.FriendRequestRepository;
import com.example.sbb.domain.user.UserRepository;
import com.example.sbb.repository.QuizQuestionRepository;
import com.example.sbb.repository.FolderRepository;
import com.example.sbb.repository.FriendShareRequestRepository;
import com.example.sbb.domain.quiz.QuizQuestion;
import com.example.sbb.domain.Folder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FriendService {
    private final FriendRepository friendRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final UserRepository userRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final FolderRepository folderRepository;
    private final FriendShareRequestRepository friendShareRequestRepository;

    public List<Friend> myFriends(SiteUser user) {
        return friendRepository.findByFrom(user);
    }

    public List<SiteUser> resolveFriendTargets(SiteUser me, List<Long> friendIds) {
        if (me == null || friendIds == null || friendIds.isEmpty()) return List.of();
        return friendIds.stream()
                .map(friendRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(f -> f.getFrom() != null && f.getFrom().getId().equals(me.getId()))
                .map(Friend::getTo)
                .collect(Collectors.toList());
    }

    public List<FriendRequest> pendingInbox(SiteUser user) {
        return friendRequestRepository.findByToUserAndStatus(user, FriendRequest.Status.PENDING);
    }

    public List<FriendRequest> pendingSent(SiteUser user) {
        return friendRequestRepository.findByFromUserAndStatus(user, FriendRequest.Status.PENDING);
    }

    public List<FriendRequest> acceptedInbox(SiteUser user) {
        return friendRequestRepository.findByToUserAndStatus(user, FriendRequest.Status.ACCEPTED);
    }

    public List<FriendRequest> acceptedSent(SiteUser user) {
        return friendRequestRepository.findByFromUserAndStatus(user, FriendRequest.Status.ACCEPTED);
    }

    @Transactional
    public boolean addFriend(SiteUser me, String targetUsername) {
        if (me == null || targetUsername == null || targetUsername.isBlank()) return false;
        if (me.getUsername().equals(targetUsername)) return false;
        Optional<SiteUser> targetOpt = userRepository.findByUsername(targetUsername.trim());
        if (targetOpt.isEmpty()) return false;
        SiteUser target = targetOpt.get();
        if (friendRepository.existsByFromAndTo(me, target)) return true;
        Friend f = new Friend();
        f.setFrom(me);
        f.setTo(target);
        friendRepository.save(f);

        // 양방향 기록
        if (!friendRepository.existsByFromAndTo(target, me)) {
            Friend back = new Friend();
            back.setFrom(target);
            back.setTo(me);
            friendRepository.save(back);
        }
        return true;
    }

    @Transactional
    public boolean removeFriend(SiteUser me, Long friendId) {
        if (me == null || friendId == null) return false;
        var friendOpt = friendRepository.findById(friendId);
        if (friendOpt.isEmpty()) return false;
        Friend f = friendOpt.get();
        if (!f.getFrom().getId().equals(me.getId())) return false;
        SiteUser other = f.getTo();
        friendRepository.delete(f);
        friendRepository.findByFromAndTo(other, me).ifPresent(friendRepository::delete);
        return true;
    }

    @Transactional
    public boolean shareQuestionToFriend(SiteUser me, Long friendId, Long questionId) {
        if (me == null || friendId == null || questionId == null) return false;
        var friendOpt = friendRepository.findById(friendId);
        if (friendOpt.isEmpty()) return false;
        Friend f = friendOpt.get();
        if (!f.getFrom().getId().equals(me.getId())) return false;
        SiteUser target = f.getTo();

        QuizQuestion q = quizQuestionRepository.findById(questionId).orElse(null);
        if (q == null || !q.getUser().getId().equals(me.getId())) return false;

        Folder targetFolder = null;
        if (q.getFolder() != null) {
            String folderName = q.getFolder().getName();
            targetFolder = folderRepository.findByUserAndName(target, folderName).orElseGet(() -> {
                Folder nf = new Folder();
                nf.setName(folderName);
                nf.setUser(target);
                return folderRepository.save(nf);
            });
        }

        QuizQuestion copy = new QuizQuestion();
        copy.setUser(target);
        copy.setDocument(null);
        copy.setFolder(targetFolder);
        copy.setNumberTag(q.getNumberTag());
        copy.setQuestionText(q.getQuestionText());
        copy.setChoices(q.getChoices());
        copy.setMultipleChoice(q.isMultipleChoice());
        copy.setAnswer(q.getAnswer());
        copy.setExplanation(q.getExplanation());
        copy.setSolved(false);
        copy.setCorrect(null);
        quizQuestionRepository.save(copy);
        return true;
    }

    @Transactional
    public boolean sendRequest(SiteUser me, String targetUsername) {
        if (me == null || targetUsername == null || targetUsername.isBlank()) return false;
        if (me.getUsername().equals(targetUsername)) return false;
        Optional<SiteUser> targetOpt = userRepository.findByUsername(targetUsername.trim());
        if (targetOpt.isEmpty()) return false;
        SiteUser target = targetOpt.get();
        if (friendRepository.existsByFromAndTo(me, target)) return true;
        if (friendRequestRepository.existsByFromUserAndToUserAndStatus(me, target, FriendRequest.Status.PENDING)) return true;
        FriendRequest req = new FriendRequest();
        req.setFromUser(me);
        req.setToUser(target);
        req.setStatus(FriendRequest.Status.PENDING);
        friendRequestRepository.save(req);
        return true;
    }

    @Transactional
    public boolean acceptRequest(Long reqId, SiteUser me) {
        var reqOpt = friendRequestRepository.findByIdAndToUser(reqId, me);
        if (reqOpt.isEmpty()) return false;
        FriendRequest req = reqOpt.get();
        req.setStatus(FriendRequest.Status.ACCEPTED);
        friendRequestRepository.save(req);
        addFriend(req.getToUser(), req.getFromUser().getUsername());
        return true;
    }

    @Transactional
    public boolean rejectRequest(Long reqId, SiteUser me) {
        var reqOpt = friendRequestRepository.findByIdAndToUser(reqId, me);
        if (reqOpt.isEmpty()) return false;
        FriendRequest req = reqOpt.get();
        req.setStatus(FriendRequest.Status.REJECTED);
        friendRequestRepository.save(req);
        return true;
    }

    public List<FriendRequest> inbox(SiteUser me) {
        return friendRequestRepository.findByToUserAndStatus(me, FriendRequest.Status.PENDING);
    }

    // ===== 함께 풀기 공유 =====
    @Transactional
    public boolean sendShareRequests(SiteUser me, List<Long> friendIds, Long questionId) {
        if (me == null || friendIds == null || friendIds.isEmpty() || questionId == null) return false;
        QuizQuestion q = quizQuestionRepository.findById(questionId).orElse(null);
        if (q == null || !q.getUser().getId().equals(me.getId())) return false;
        for (Long fid : friendIds) {
            var friendOpt = friendRepository.findById(fid);
            if (friendOpt.isEmpty()) continue;
            Friend fr = friendOpt.get();
            SiteUser target = fr.getTo();
            Folder targetFolder = null;
            if (q.getFolder() != null) {
                String folderName = q.getFolder().getName();
                targetFolder = folderRepository.findByUserAndName(target, folderName).orElseGet(() -> {
                    Folder nf = new Folder();
                    nf.setName(folderName);
                    nf.setUser(target);
                    return folderRepository.save(nf);
                });
            }
            QuizQuestion copy = new QuizQuestion();
            copy.setUser(target);
            copy.setDocument(null);
            copy.setFolder(targetFolder);
            copy.setNumberTag(q.getNumberTag());
            copy.setQuestionText(q.getQuestionText());
            copy.setChoices(q.getChoices());
            copy.setMultipleChoice(q.isMultipleChoice());
            copy.setAnswer(q.getAnswer());
            copy.setExplanation(q.getExplanation());
            copy.setSolved(false);
            copy.setCorrect(null);
            quizQuestionRepository.save(copy);
            FriendShareRequest req = new FriendShareRequest();
            req.setFromUser(me);
            req.setToUser(target);
            req.setQuestion(copy);
            req.setSenderQuestion(q);
            req.setStatus(FriendShareRequest.Status.PENDING);
            friendShareRequestRepository.save(req);
        }
        return true;
    }

    @Transactional
    public QuizQuestion resolveShareQuestionForViewer(FriendShareRequest req, SiteUser viewer) {
        if (req == null || viewer == null) return null;
        QuizQuestion q = req.getQuestion();
        if (q != null && q.getUser() != null && q.getUser().getId().equals(viewer.getId())) {
            return q;
        }
        if (req.getFromUser() == null || q == null) return q;
        if (!req.getFromUser().getId().equals(viewer.getId())) {
            return q;
        }
        QuizQuestion senderCopy = req.getSenderQuestion();
        if (senderCopy != null && senderCopy.getUser() != null
                && senderCopy.getUser().getId().equals(viewer.getId())) {
            return senderCopy;
        }

        Folder targetFolder = null;
        if (q.getFolder() != null) {
            String folderName = q.getFolder().getName();
            targetFolder = folderRepository.findByUserAndName(viewer, folderName).orElseGet(() -> {
                Folder nf = new Folder();
                nf.setName(folderName);
                nf.setUser(viewer);
                return folderRepository.save(nf);
            });
        }

        QuizQuestion copy = new QuizQuestion();
        copy.setUser(viewer);
        copy.setDocument(null);
        copy.setFolder(targetFolder);
        copy.setNumberTag(q.getNumberTag());
        copy.setQuestionText(q.getQuestionText());
        copy.setChoices(q.getChoices());
        copy.setMultipleChoice(q.isMultipleChoice());
        copy.setAnswer(q.getAnswer());
        copy.setExplanation(q.getExplanation());
        copy.setSolved(false);
        copy.setCorrect(null);
        quizQuestionRepository.save(copy);
        req.setSenderQuestion(copy);
        friendShareRequestRepository.save(req);
        return copy;
    }

    public List<FriendShareRequest> pendingShareInbox(SiteUser me) {
        return friendShareRequestRepository.findByToUserAndStatus(me, FriendShareRequest.Status.PENDING);
    }

    public List<FriendShareRequest> sentSharePending(SiteUser me) {
        return friendShareRequestRepository.findByFromUserAndStatus(me, FriendShareRequest.Status.PENDING);
    }

    public List<FriendShareRequest> sentShareAccepted(SiteUser me) {
        return friendShareRequestRepository.findByFromUserAndStatus(me, FriendShareRequest.Status.ACCEPTED);
    }

    @Transactional
    public boolean acceptShareRequest(Long reqId, SiteUser me) {
        var reqOpt = friendShareRequestRepository.findByIdAndToUser(reqId, me);
        if (reqOpt.isEmpty()) return false;
        FriendShareRequest req = reqOpt.get();
        QuizQuestion q = req.getQuestion();
        if (q != null && q.getUser() != null && q.getUser().getId().equals(me.getId())) {
            req.setStatus(FriendShareRequest.Status.ACCEPTED);
            friendShareRequestRepository.save(req);
            return true;
        }
        req.setStatus(FriendShareRequest.Status.ACCEPTED);
        friendShareRequestRepository.save(req);

        if (q == null) return true;
        Folder targetFolder = null;
        if (q.getFolder() != null) {
            targetFolder = folderRepository.findByUserAndName(me, q.getFolder().getName())
                    .orElseGet(() -> {
                        Folder nf = new Folder();
                        nf.setName(q.getFolder().getName());
                        nf.setUser(me);
                        return folderRepository.save(nf);
                    });
        }
        QuizQuestion copy = new QuizQuestion();
        copy.setUser(me);
        copy.setDocument(null);
        copy.setFolder(targetFolder);
        copy.setNumberTag(q.getNumberTag());
        copy.setQuestionText(q.getQuestionText());
        copy.setChoices(q.getChoices());
        copy.setMultipleChoice(q.isMultipleChoice());
        copy.setAnswer(q.getAnswer());
        copy.setExplanation(q.getExplanation());
        copy.setSolved(false);
        copy.setCorrect(null);
        quizQuestionRepository.save(copy);
        return true;
    }

    @Transactional
    public boolean rejectShareRequest(Long reqId, SiteUser me) {
        var reqOpt = friendShareRequestRepository.findByIdAndToUser(reqId, me);
        if (reqOpt.isEmpty()) return false;
        FriendShareRequest req = reqOpt.get();
        req.setStatus(FriendShareRequest.Status.REJECTED);
        friendShareRequestRepository.save(req);
        return true;
    }

}
