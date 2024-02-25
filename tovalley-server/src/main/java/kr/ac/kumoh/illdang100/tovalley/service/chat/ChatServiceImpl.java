package kr.ac.kumoh.illdang100.tovalley.service.chat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.ChatMessage;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.ChatMessageRepository;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.ChatNotification;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.ChatNotificationRepository;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.ChatRoom;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.ChatRoomRepository;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.kafka.Message;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.kafka.Notification;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.kafka.NotificationType;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.redis.ChatRoomParticipant;
import kr.ac.kumoh.illdang100.tovalley.domain.chat.redis.ChatRoomParticipantRedisRepository;
import kr.ac.kumoh.illdang100.tovalley.domain.member.Member;
import kr.ac.kumoh.illdang100.tovalley.domain.member.MemberRepository;
import kr.ac.kumoh.illdang100.tovalley.dto.chat.ChatReqDto.CreateNewChatRoomReqDto;
import kr.ac.kumoh.illdang100.tovalley.dto.chat.ChatRespDto.ChatMessageListRespDto;
import kr.ac.kumoh.illdang100.tovalley.dto.chat.ChatRespDto.ChatMessageRespDto;
import kr.ac.kumoh.illdang100.tovalley.dto.chat.ChatRespDto.ChatRoomRespDto;
import kr.ac.kumoh.illdang100.tovalley.dto.chat.ChatRespDto.CreateNewChatRoomRespDto;
import kr.ac.kumoh.illdang100.tovalley.handler.ex.CustomApiException;
import kr.ac.kumoh.illdang100.tovalley.util.ChatUtil;
import kr.ac.kumoh.illdang100.tovalley.util.KafkaVO;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatServiceImpl implements ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final MemberRepository memberRepository;
    private final KafkaSender kafkaSender;
    private final ChatRoomParticipantRedisRepository chatRoomParticipantRedisRepository;
    private final ChatNotificationRepository chatNotificationRepository;
    private final MongoTemplate mongoTemplate;

    /**
     * 특정 상대방과의 채팅방이 존재하지 않는다면, 새로운 채팅방 생성해서 응답 만약 상대방과 기존 채팅방이 존재한다면 기존 채팅방 pk 응답
     *
     * @param senderId   채팅방 생성을 요청하는 사용자 pk
     * @param requestDto
     * @return
     */
    @Override
    @Transactional
    public CreateNewChatRoomRespDto createOrGetChatRoom(Long senderId, CreateNewChatRoomReqDto requestDto) {

        // 요청하는 사용자와 상대방 간에 채팅방이 있는지 확인하는 로직 필요
        String recipientNick = requestDto.getRecipientNick();

        Optional<ChatRoom> findChatRoomOpt
                = chatRoomRepository.findByMemberIdAndOtherMemberNickname(senderId, recipientNick);

        // 존재한다면 기존 채팅방 Pk 반환
        if (findChatRoomOpt.isPresent()) {
            return new CreateNewChatRoomRespDto(false, findChatRoomOpt.get().getId());
        }

        List<Member> members = memberRepository.findByIdOrNickname(senderId, recipientNick);
        validateMembers(members);

        Member sender = getSender(members, senderId);
        Member recipient = getRecipient(members, senderId);

        return createNewChatRoom(sender, recipient);
    }

    private void validateMembers(List<Member> members) {
        if (members.size() != 2) {
            throw new CustomApiException("사용자가 존재하지 않습니다");
        }
    }

    private Member getSender(List<Member> members, Long senderId) {
        for (Member member : members) {
            if (member.getId().equals(senderId)) {
                return member;
            }
        }
        throw new CustomApiException("발신자를 찾을 수 없습니다");
    }

    private Member getRecipient(List<Member> members, Long senderId) {
        for (Member member : members) {
            if (!member.getId().equals(senderId)) {
                return member;
            }
        }
        throw new CustomApiException("수신자를 찾을 수 없습니다");
    }

    private CreateNewChatRoomRespDto createNewChatRoom(Member sender, Member recipient) {
        ChatRoom chatRoom = ChatRoom.createNewChatRoom(sender, recipient);
        ChatRoom createdChatRoom = chatRoomRepository.save(chatRoom);
        return new CreateNewChatRoomRespDto(true, createdChatRoom.getId());
    }

    /**
     * 채팅방 목록을 Slice 형태로 전달
     *
     * @param memberId 채팅방 목록을 요청하는 사용자 pk
     * @param pageable 페이징 상세 정보
     * @return 채팅방 목록
     */
    @Override
    public Slice<ChatRoomRespDto> getChatRooms(Long memberId, Pageable pageable) {

        Slice<ChatRoomRespDto> sliceChatRoomsByMemberId
                = chatRoomRepository.findSliceChatRoomsByMemberId(memberId, pageable);

        List<ChatRoomRespDto> processedChatRooms = processChatRooms(sliceChatRoomsByMemberId.getContent(), memberId);

        // 마지막 메시지 시간으로 내림차순 정렬
        List<ChatRoomRespDto> sortedChatRooms = sortChatRoomsByLastMessageTime(processedChatRooms);

        return new SliceImpl<>(sortedChatRooms, pageable, sliceChatRoomsByMemberId.hasNext());
    }

    private List<ChatRoomRespDto> processChatRooms(List<ChatRoomRespDto> chatRooms, Long memberId) {
        for (ChatRoomRespDto chatRoom : chatRooms) {
            processSingleChatRoom(chatRoom, memberId);
        }
        return chatRooms;
    }

    private void processSingleChatRoom(ChatRoomRespDto chatRoom, Long memberId) {
        long unreadMessageCount = countUnReadMessages(chatRoom.getChatRoomId(), memberId);
        chatRoom.changeUnReadMessageCount(unreadMessageCount);
        updateChatRoomWithLastMessageIfExists(chatRoom);
    }

    private void updateChatRoomWithLastMessageIfExists(ChatRoomRespDto chatRoom) {
        Optional<ChatMessage> lastMessageOpt = findLastMessageInChatRoom(chatRoom.getChatRoomId());
        lastMessageOpt.ifPresent((lastMessage) -> updateChatRoomWithLastMessage(chatRoom, lastMessage));
    }

    private Optional<ChatMessage> findLastMessageInChatRoom(Long chatRoomId) {
        return chatMessageRepository.findTopByChatRoomIdOrderByCreatedAtDesc(chatRoomId);
    }

    private void updateChatRoomWithLastMessage(ChatRoomRespDto chatRoom, ChatMessage lastMessage) {
        chatRoom.changeLastMessage(lastMessage.getContent(),
                ChatUtil.convertZdtStringToLocalDateTime(lastMessage.getCreatedAt()));
    }

    private long countUnReadMessages(Long chatRoomId, Long memberId) {
        Query query = new Query(Criteria.where("chatRoomId").is(chatRoomId)
                .and("readCount").is(1)
                .and("senderId").ne(memberId));

        return mongoTemplate.count(query, ChatMessage.class);
    }

    private List<ChatRoomRespDto> sortChatRoomsByLastMessageTime(List<ChatRoomRespDto> chatRooms) {
        return chatRooms.stream()
                .sorted(Comparator.comparing(ChatRoomRespDto::getLastMessageTime, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public void exitChatRoom(Long memberId, Long chatRoomId) {

    }

    /**
     * 채팅 메시지 목록 조회
     *
     * @param memberId   채팅 메시지 목록을 요청하는 사용자 pk
     * @param chatRoomId 채팅방 pk
     * @param pageable   페이징 상세 정보
     * @return 채팅 메시지 목록
     */
    @Override
    public ChatMessageListRespDto getChatMessages(Long memberId, Long chatRoomId, Pageable pageable) {
        // 0. 요청한 사용자가 속한 채팅방이 맞는지 검증
        validateChatRoom(memberId, chatRoomId);

        // 1. MongoDB로부터 원하는 페이지 번호와 크기에 해당하는 데이터를 가져옵니다.
        Slice<ChatMessage> chatMessageSlice = chatMessageRepository.findByChatRoomIdOrderByCreatedAtDesc(chatRoomId, pageable);

        // 2. 가져온 데이터를 DTO로 변환합니다.
        List<ChatMessageRespDto> chatMessageRespDtos = chatMessageSlice.getContent().stream()
                .map(chatMessage -> new ChatMessageRespDto(chatMessage, memberId))
                .collect(Collectors.toList());

        // 3. 변환된 DTO를 기반으로 새로운 Slice를 생성합니다.
        Slice<ChatMessageRespDto> chatMessageRespDtoSlice = new SliceImpl<>(chatMessageRespDtos, pageable, chatMessageSlice.hasNext());

        // 4. Slice 객체와 함께 응답 DTO를 생성하고 반환합니다.
        return new ChatMessageListRespDto(memberId, chatRoomId, chatMessageRespDtoSlice);
    }

    private void validateChatRoom(Long memberId, Long chatRoomId) {
        Optional<ChatRoom> chatRoom = chatRoomRepository.findByIdAndMemberId(chatRoomId, memberId);
        if (chatRoom.isEmpty()) {
            throw new CustomApiException("해당 사용자가 속한 채팅방이 아닙니다");
        }
    }

    @Override
    @Transactional
    public void sendMessage(Message message, Long senderId) {

        Long chatRoomId = message.getChatRoomId();
        boolean allMembersParticipatingInChatRoom = isAllMembersParticipatingInChatRoom(chatRoomId);
        processMessage(message, senderId, allMembersParticipatingInChatRoom);

        ChatMessage chatMessage = message.convertToChatMessage();
        chatMessageRepository.save(chatMessage);

        if (!allMembersParticipatingInChatRoom) {
            // 상대방에게 알림 전송
            sendNotification(message, senderId, chatRoomId);
        }

        // 메시지 전송
        kafkaSender.sendMessage(KafkaVO.KAFKA_CHAT_TOPIC, message);
    }

    private boolean isAllMembersParticipatingInChatRoom(Long chatRoomId) {
        List<ChatRoomParticipant> chatRoomParticipants = getChatRoomParticipants(chatRoomId);
        return chatRoomParticipants.size() == ChatUtil.MAX_PARTICIPANTS_PER_CHATROOM;
    }

    private void processMessage(Message message, Long senderId, boolean allMembersParticipatingInChatRoom) {
        int readCount = calculateReadCount(allMembersParticipatingInChatRoom);
        updateMessageBeforeSending(message, senderId, readCount);
    }

    private int calculateReadCount(boolean allMembersParticipatingInChatRoom) {
        return allMembersParticipatingInChatRoom ? 0 : 1;
    }

    private void updateMessageBeforeSending(Message message, Long senderId, int readCount) {
        message.prepareMessageForSending(senderId, ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toString(), readCount);
    }

    private void sendNotification(Message message, Long senderId, Long chatRoomId) {
        try {
            ChatRoom findChatRoom = chatRoomRepository.findByIdWithMembers(chatRoomId)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 채팅방입니다."));

            Member recipient = getRecipientFromChatRoom(findChatRoom, senderId);
            Member sender = getSenderFromChatRoom(findChatRoom, senderId);

            // 알림 정보를 RDBMS에 저장
            ChatNotification chatNotification = new ChatNotification(sender, recipient.getId(), chatRoomId,
                    message.getContent());
            chatNotificationRepository.save(chatNotification);

            // Kafka로는 Notification 객체 전송
            Notification notification = new Notification(chatRoomId, recipient.getId(), sender.getNickname(),
                    LocalDateTime.now(), message.getContent(), NotificationType.CHAT);

            // "알림 토픽 + {memberId}"로 알림 메시지 전송하기!!
            kafkaSender.sendNotification(KafkaVO.KAFKA_NOTIFICATION_TOPIC, notification);
        } catch (Exception e) {
            log.error("메시지 알림 전송 에러");
        }
    }

    private Member getRecipientFromChatRoom(ChatRoom findChatRoom, Long senderId) {
        return findChatRoom.getSender().getId().equals(senderId) ? findChatRoom.getRecipient()
                : findChatRoom.getSender();
    }

    private Member getSenderFromChatRoom(ChatRoom findChatRoom, Long senderId) {
        return findChatRoom.getSender().getId().equals(senderId) ? findChatRoom.getSender()
                : findChatRoom.getRecipient();
    }


    @Override
    public void saveChatRoomParticipantToRedis(Long memberId, Long chatRoomId) {
        ChatRoomParticipant chatRoomParticipant = new ChatRoomParticipant(memberId, chatRoomId);
        chatRoomParticipantRedisRepository.save(chatRoomParticipant);
    }

    @Override
    public void deleteChatRoomParticipantFromRedis(Long memberId) {
        List<ChatRoomParticipant> chatRoomParticipants
                = chatRoomParticipantRedisRepository.findByMemberId(memberId);

        if (chatRoomParticipants != null && !chatRoomParticipants.isEmpty()) {
            chatRoomParticipantRedisRepository.deleteAll(chatRoomParticipants);
        }
    }

    @Override
    public void updateUnreadMessages(Long memberId, Long chatRoomId) {
        Query query = new Query(Criteria.where("chatRoomId")
                .is(chatRoomId)
                .and("readCount").is(1)
                .and("senderId").ne(memberId));

        Update update = new Update().set("readCount", 0);
        mongoTemplate.updateMulti(query, update, ChatMessage.class);
    }

    @Override
    public Optional<Long> getOtherMemberIdByChatRoomId(Long chatRoomId, Long memberId) {
        List<ChatRoomParticipant> chatRoomParticipants = getChatRoomParticipants(chatRoomId);

        return chatRoomParticipants == null ? Optional.empty() : findOtherMember(chatRoomParticipants, memberId);
    }

    private List<ChatRoomParticipant> getChatRoomParticipants(Long chatRoomId) {
        return chatRoomParticipantRedisRepository.findByChatRoomId(chatRoomId);
    }

    private Optional<Long> findOtherMember(List<ChatRoomParticipant> chatRoomParticipants, Long memberId) {
        return chatRoomParticipants.stream()
                .map(ChatRoomParticipant::getMemberId)
                .filter(id -> !id.equals(memberId))
                .findFirst();
    }
}
