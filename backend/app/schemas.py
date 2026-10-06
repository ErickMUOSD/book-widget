from pydantic import BaseModel, Field


class BookCreate(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    author: str = Field(default="", max_length=200)
    synopsis: str = Field(default="", max_length=2000)
    total_chapters: int | None = Field(default=None, ge=1, le=500)


class BookRetry(BaseModel):
    synopsis: str | None = Field(default=None, max_length=2000)
    total_chapters: int | None = Field(default=None, ge=1, le=500)


class ProgressIn(BaseModel):
    current_chapter: int = Field(ge=0)
    spoiler_level: str | None = None


class StatePatch(BaseModel):
    sealed: bool | None = None
    spoiler_level: str | None = None


class CaptionIn(BaseModel):
    caption: str = Field(min_length=1, max_length=600)


class SceneElement(BaseModel):
    tipo: str = Field(default="objeto", max_length=20)
    nombre: str = Field(min_length=1, max_length=80)
    detalle: str = Field(default="", max_length=200)


class SceneIn(BaseModel):
    nudo: str = Field(default="", max_length=400)
    desenlace: str = Field(default="", max_length=400)
    elementos: list[SceneElement] = Field(min_length=1, max_length=6)


class DeviceCreate(BaseModel):
    name: str = Field(min_length=1, max_length=80)
