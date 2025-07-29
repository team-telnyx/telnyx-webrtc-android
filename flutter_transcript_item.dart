/// Represents a single item in a conversation transcript with the AI assistant or user.
class TranscriptItem {
  /// Unique identifier for the transcript item
  final String id;
  
  /// Role of the speaker - 'user' for user speech, 'assistant' for AI response
  final String role;
  
  /// The text content of the transcript item
  final String content;
  
  /// Timestamp when the transcript item was created
  final DateTime timestamp;

  /// Optional flag indicating if the item is a partial response
  final bool? isPartial;

  TranscriptItem({
    required this.id,
    required this.role,
    required this.content,
    required this.timestamp,
    this.isPartial,
  });

  TranscriptItem.fromJson(Map<String, dynamic> json)
      : id = json['id'] as String,
        role = json['role'] as String,
        content = json['content'] as String,
        timestamp = DateTime.parse(json['timestamp'] as String),
        isPartial = json['isPartial'] as bool? ?? false;

  Map<String, dynamic> toJson() => {
        'id': id,
        'role': role,
        'content': content,
        'timestamp': timestamp.toIso8601String(),
        'isPartial': isPartial ?? false,
      };

  @override
  String toString() => 'TranscriptItem(id: $id, role: $role, content: $content, timestamp: $timestamp, isPartial: $isPartial)';

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is TranscriptItem &&
        other.id == id &&
        other.role == role &&
        other.content == content &&
        other.timestamp == timestamp &&
        other.isPartial == isPartial;
  }

  @override
  int get hashCode => Object.hash(id, role, content, timestamp, isPartial);
}