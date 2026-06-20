# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PaperLoom is an evidence-grounded RAG workbench for structured research paper reading. The repository directory and some legacy physical database names still use PaiSmart/file terminology, but the product story, public API, and frontend contract should be paper-centered.

PaperLoom supports PDF paper upload, asynchronous parsing/indexing, page-aware chunk retrieval, source-grounded chat, persistent evidence references, and basic editable paper metadata. It should not be described as a generic enterprise knowledge-base assistant.

## Development Environment Setup

### Prerequisites
- Java 17
- Maven 3.8.6+
- Node.js 18.20.0+
- pnpm 8.7.0+
- MySQL 8.0
- Elasticsearch 8.10.0
- MinIO 8.5.12
- Kafka 3.2.1
- Redis 7.0.11
- Docker (optional for services)

### Quick Start with Docker
```bash
# Start all services
cd docs && docker-compose up -d

# Backend
mvn spring-boot:run

# Frontend
cd frontend && pnpm install && pnpm dev
```

## Common Development Commands

### Backend (Spring Boot)
```bash
# Run application
mvn spring-boot:run

# Build
mvn clean package

# Test
mvn test

# Run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Frontend (Vue 3 + TypeScript)
```bash
# Install dependencies
cd frontend && pnpm install

# Development server
pnpm dev

# Build for production
pnpm build

# Type checking
pnpm typecheck

# Linting
pnpm lint

# Preview build
pnpm preview
```

## Architecture Overview

### Backend Structure
```
`src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java      # Main application entry
├── client/                       # External API clients (DeepSeek, Embedding)
├── config/                       # Configuration classes (Security, JWT, etc.)
├── consumer/                     # Kafka consumers for async processing
├── controller/                   # REST API endpoints
├── entity/                       # JPA entities
├── exception/                    # Custom exceptions
├── handler/                      # WebSocket handlers
├── model/                        # Domain models
├── repository/                   # Data access layer
├── service/                      # Business logic layer
└── utils/                        # Utility classes
````

### Frontend Structure
```
frontend/src/
├── assets/                       # Static assets (SVG, images)
├── components/                   # Reusable Vue components
├── layouts/                      # Page layouts
├── router/                       # Vue Router configuration
├── service/                      # API integration
├── store/                        # Pinia state management
├── views/                        # Page components
└── utils/                        # Utility functions
```

## Key Components

### Core Services
- **PaperController / PaperUploadController / PaperSearchController**: Paper library, PDF upload, preview, metadata, evidence reference, and search APIs
- **PaperService**: Handles paper deletion, access lists, preview, metadata updates, and indexing retries
- **ElasticsearchService**: Manages paper chunk indexing and search
- **VectorizationService**: Converts text to embeddings using AI models
- **ChatHandler**: Processes AI chat interactions with RAG
- **UserService**: User authentication and management
- **ConversationService**: Chat history, session management, and MySQL-backed reference evidence recovery

### AI Integration
- **DeepSeek API**: Primary LLM for chat responses
- **Embedding API**: Text-embedding-v4 for paper chunk vectorization
- **RAG Pipeline**: Paper PDF → page-aware chunk → embedding → hybrid retrieval → source-grounded response

### Multi-tenant Architecture
- **Organization Tags**: Supports multi-tenant isolation
- **Permission System**: Public/private/organization-scoped paper access control
- **User-Organization Mapping**: Flexible user-to-org relationships

## Configuration Files

### Backend Configuration
- `application.yml`: Main configuration with database, Redis, Kafka, AI services
- `application-dev.yml`: Development-specific settings
- `application-docker.yml`: Docker deployment settings

### Frontend Configuration
- `vite.config.ts`: Vite build configuration
- `tsconfig.json`: TypeScript configuration
- `pnpm-workspace.yaml`: Workspace configuration for monorepo

## Database Schema

The application uses MySQL as the primary database with JPA/Hibernate for ORM. Key entities include:
- `User`: User accounts and authentication
- `Paper`: paper upload metadata, processing status, visibility, and editable paper metadata
- `PaperTextChunk`: page-aware paper chunks
- `Conversation`: Chat history and persisted reference mappings
- `OrganizationTag`: Multi-tenant organization structure
- `ChunkInfo`: legacy physical table/entity name for upload chunks

## External Dependencies

### Services
- **Elasticsearch 8.10.0**: Paper chunk search and vector storage
- **Kafka 3.2.1**: Message queue for async file processing
- **Redis 7.0.11**: Caching and session management
- **MinIO 8.5.12**: File storage service
- **MySQL 8.0**: Primary database

### AI Services
- **DeepSeek API**: LLM for generating responses
- **DashScope Embedding**: Text-embedding-v4 for paper chunk vectorization

## Development Workflow

### Adding New Features
1. Backend: Create entity → repository → service → controller
2. Frontend: Create API service → store module → Vue component → router configuration
3. Update database schema if needed (JPA auto-generates DDL)
4. Test with both unit and integration tests

### API Development
- Follow RESTful conventions
- Use proper HTTP status codes
- Implement authentication/authorization via JWT
- Add request validation and error handling

### Frontend Development
- Use Vue 3 Composition API with TypeScript
- Follow established component patterns in `/src/components/`
- Use Pinia for state management
- Implement proper TypeScript types for API responses

## Testing

### Backend Testing
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=UserServiceTest

# Run with coverage
mvn clean verify
```

### Frontend Testing
```bash
# Type checking
pnpm typecheck

# Linting
pnpm lint

# Build verification
pnpm build
```

## Deployment

### Docker Deployment
```bash
# Build backend
mvn clean package

# Build frontend
cd frontend && pnpm build

# Start services
cd docs && docker-compose up -d
```

### Environment Variables
Key configuration variables that should be set in production:
- `JWT_SECRET_KEY`: JWT signing secret
- `DEEPSEEK_API_KEY`: DeepSeek API credentials
- `EMBEDDING_API_KEY`: Embedding service API key
- Database credentials and service URLs

## Security Considerations

- JWT-based authentication with Spring Security
- Role-based access control (admin/user)
- Organization-level data isolation
- File upload validation and size limits
- CORS configuration for frontend integration
- Input validation and sanitization

## Performance Considerations

- Elasticsearch for efficient paper chunk search
- Redis caching for frequently accessed data
- Kafka for asynchronous file processing
- PDF chunking for large paper processing
- Vector embeddings for semantic search
- Connection pooling for database and external services
