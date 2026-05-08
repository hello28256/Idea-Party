package com.ideaparty.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "characters")
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    private String avatar;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection
    @CollectionTable(name = "character_expertise", joinColumns = @JoinColumn(name = "character_id"))
    @Column(name = "expertise")
    private List<String> expertise = new ArrayList<>();

    private String era;

    @Column(name = "speaking_style", columnDefinition = "TEXT")
    private String speakingStyle;

    @Column(columnDefinition = "TEXT")
    private String persona;

    @ManyToMany(mappedBy = "characters")
    private Set<Room> rooms = new HashSet<>();

    public Character() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getExpertise() { return expertise; }
    public void setExpertise(List<String> expertise) { this.expertise = expertise; }

    public String getEra() { return era; }
    public void setEra(String era) { this.era = era; }

    public String getSpeakingStyle() { return speakingStyle; }
    public void setSpeakingStyle(String speakingStyle) { this.speakingStyle = speakingStyle; }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public Set<Room> getRooms() { return rooms; }
    public void setRooms(Set<Room> rooms) { this.rooms = rooms; }
}
